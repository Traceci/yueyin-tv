package com.fnmusic.tv.update

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun interface UpdateManifestSource {
    suspend fun fetchManifest(): UpdateManifest
}

/**
 * 依次尝试多个清单地址：jsDelivr 镜像在国内更稳，raw.githubusercontent.com 兜底。
 * 任何一个成功即返回，全部失败才把最后一个错误抛出。
 */
internal class FallbackManifestSource(
    private val sources: List<UpdateManifestSource>,
) : UpdateManifestSource {
    override suspend fun fetchManifest(): UpdateManifest {
        var lastFailure: Throwable? = null
        for (source in sources) {
            try {
                return source.fetchManifest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        throw lastFailure ?: UpdateFailure("没有可用的更新地址")
    }
}

internal class UpdateClient(
    private val endpoint: String,
    private val client: OkHttpClient = defaultUpdateHttpClient(),
    private val policy: UpdateUrlPolicy = YueyinUpdatePolicy,
) : UpdateManifestSource {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    override suspend fun fetchManifest(): UpdateManifest {
        var endpointUri = policy.requireManifest(validateHttpsUri(endpoint, "更新地址"))
        var hops = 0
        while (true) {
            val request = Request.Builder()
                .url(endpointUri.toString())
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            val response = client.newCall(request).await()
            // jsDelivr 等镜像会先回到规范地址，这里手动跟随，但每一跳都要过渠道校验。
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw UpdateFailure("更新地址跳转缺少目标")
                if (++hops > MAX_UPDATE_REDIRECTS) throw UpdateFailure("更新地址跳转次数过多")
                endpointUri = policy.requireManifest(
                    policy.resolveRedirect(endpointUri, location, "更新地址"),
                )
                continue
            }
            return response.use { current ->
                if (!current.isSuccessful) throw UpdateFailure("检查更新失败（${current.code}）")
                val contentLength = current.body.contentLength()
                if (contentLength > MAX_UPDATE_MANIFEST_BYTES) throw UpdateFailure("更新信息过大")
                val bytes = current.body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (output.size() <= MAX_UPDATE_MANIFEST_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_UPDATE_MANIFEST_BYTES + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > MAX_UPDATE_MANIFEST_BYTES) throw UpdateFailure("更新信息过大")
                val dto = try {
                    json.decodeFromString<UpdateManifestDto>(bytes.decodeToString())
                } catch (_: SerializationException) {
                    throw UpdateFailure("更新信息格式无效")
                } catch (_: IllegalArgumentException) {
                    throw UpdateFailure("更新信息格式无效")
                }
                validateManifest(dto, policy)
            }
        }
    }
}

internal fun validateManifest(
    dto: UpdateManifestDto,
    policy: UpdateUrlPolicy = YueyinUpdatePolicy,
): UpdateManifest {
    if (dto.schemaVersion != 1) throw UpdateFailure("暂不支持这个更新信息版本")
    if (dto.packageName != UPDATE_PACKAGE_NAME) throw UpdateFailure("更新包名不匹配")
    if (dto.versionName.isBlank() || dto.versionName.length > 64 || dto.versionCode <= 0) {
        throw UpdateFailure("更新版本号无效")
    }
    if (dto.title.isBlank() || dto.title.length > 120 || dto.notes.length > MAX_UPDATE_NOTES_LENGTH) {
        throw UpdateFailure("更新说明格式无效")
    }
    if (dto.apk.size <= 0 || !LOWERCASE_SHA256.matches(dto.apk.sha256)) {
        throw UpdateFailure("更新文件信息无效")
    }
    val apkUri = policy.requireApk(validateHttpsUri(dto.apk.url, "APK 地址"))
    if (!isValidPublishedAt(dto.publishedAt)) throw UpdateFailure("发布时间无效")
    validateHttpsUri(dto.githubReleaseUrl, "GitHub Release 地址")
    return UpdateManifest(
        versionName = dto.versionName,
        versionCode = dto.versionCode,
        title = dto.title,
        notes = dto.notes,
        apkUrl = apkUri.toString(),
        apkSize = dto.apk.size,
        apkSha256 = dto.apk.sha256,
    )
}

private fun isValidPublishedAt(value: String): Boolean {
    val match = PUBLISHED_AT_PATTERN.matchEntire(value) ?: return false
    val fraction = match.groups[1]?.value.orEmpty().padEnd(3, '0').take(3)
    val zoneStart = sequenceOf(value.indexOf('Z', 10), value.indexOf('+', 10), value.indexOf('-', 10))
        .filter { it >= 0 }
        .minOrNull() ?: return false
    val zone = value.substring(zoneStart).let { if (it == "Z") "+0000" else it.replace(":", "") }
    val normalized = value.substring(0, zoneStart).substringBefore('.') + "." + fraction + zone
    val position = ParsePosition(0)
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
        isLenient = false
    }.parse(normalized, position)
    return position.index == normalized.length && position.errorIndex < 0
}

internal fun validateHttpsUri(value: String, label: String): URI {
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw UpdateFailure("$label 无效")
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.fragment != null
    ) {
        throw UpdateFailure("$label 必须使用安全的 HTTPS 地址")
    }
    return uri
}

internal class UpdateFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
private val PUBLISHED_AT_PATTERN = Regex(
    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.(\\d{1,9}))?(?:Z|[+-]\\d{2}:\\d{2})$",
)

private fun defaultUpdateHttpClient() = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
