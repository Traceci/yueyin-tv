package com.fnmusic.tv.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

internal interface UpdateApkDownloader {
    suspend fun download(manifest: UpdateManifest, onProgress: (Long) -> Unit): File
    fun cleanAll()
}

internal class UpdateDownloader(
    context: Context,
    private val policy: UpdateUrlPolicy = YueyinUpdatePolicy,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) : UpdateApkDownloader {
    private val directory = File(context.cacheDir, "updates")

    override suspend fun download(manifest: UpdateManifest, onProgress: (Long) -> Unit): File = coroutineScope {
        directory.mkdirs()
        val partial = File(directory, "${manifest.versionCode}.apk.part")
        val verified = File(directory, "${manifest.versionCode}.apk")
        partial.delete()
        verified.delete()
        val activeCall = AtomicReference<Call?>(null)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                activeCall.get()?.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                openResponse(manifest.apkUrl, activeCall).use { response ->
                    if (!response.isSuccessful) throw UpdateFailure("下载更新失败（${response.code}）")
                    val responseLength = response.body.contentLength()
                    if (responseLength >= 0 && responseLength != manifest.apkSize) {
                        throw UpdateFailure("更新文件大小不一致")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    response.body.byteStream().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > manifest.apkSize) throw UpdateFailure("更新文件大于预期")
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                onProgress(total)
                            }
                            if (total != manifest.apkSize) throw UpdateFailure("更新文件不完整")
                        }
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualSha != manifest.apkSha256) throw UpdateFailure("更新文件校验失败")
                    if (!partial.renameTo(verified)) throw UpdateFailure("无法保存已校验的更新文件")
                    verified
                }
            }
        } catch (error: IOException) {
            partial.delete()
            verified.delete()
            coroutineContext.ensureActive()
            throw error
        } catch (error: Throwable) {
            partial.delete()
            verified.delete()
            throw error
        } finally {
            cancellationWatcher.cancel()
        }
    }

    override fun cleanAll() {
        directory.listFiles()?.forEach(File::delete)
    }

    /**
     * GitHub Release 的资源地址会 302 到对象存储，OkHttp 的自动跟随被关掉了，
     * 这里手动跟，但每一跳都要求落在悦音的发布渠道里。
     */
    private fun openResponse(apkUrl: String, activeCall: AtomicReference<Call?>): Response {
        var current = policy.requireApk(validateHttpsUri(apkUrl, "APK 地址"))
        var hops = 0
        while (true) {
            val call = client.newCall(Request.Builder().url(current.toString()).build())
            activeCall.set(call)
            val response = call.execute()
            if (response.code !in 300..399) return response
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw UpdateFailure("APK 下载跳转缺少目标")
            if (++hops > MAX_UPDATE_REDIRECTS) throw UpdateFailure("APK 下载跳转次数过多")
            current = policy.requireApk(
                policy.resolveRedirect(current, location, "APK 地址"),
            )
        }
    }

    fun cleanPartial() {
        directory.listFiles { file -> file.name.endsWith(".part") }?.forEach(File::delete)
    }
}
