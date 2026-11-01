package com.yueyin.tv.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 自定义音源运行时。
 *
 * 音源脚本本身是用户导入的 JS，这里用一个隐藏 WebView 提供 LX Music 那套宿主 API
 * （lx.request / lx.utils.crypto / lx.utils.buffer），网络请求交回原生 OkHttp 执行，
 * 这样既不内置任何音源，也不受 WebView 跨域限制。
 *
 * 约定：所有 WebView 操作都在主线程串行执行，对外暴露的都是 suspend 方法。
 */
@SuppressLint("SetJavaScriptEnabled")
class OnlineSourceRuntime(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var webView: WebView? = null
    private var pageReady: CompletableDeferred<Unit>? = null
    private var loadedKey: String? = null

    private val calls = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var callSeq = 0

    private val bridge = object {
        @JavascriptInterface
        fun nativeReady() {
            pageReady?.complete(Unit)
        }

        @JavascriptInterface
        fun nativeLog(line: String?) {
            if (line != null) LxLog.add(line)
        }

        @JavascriptInterface
        fun nativeEvent(name: String?, payload: String?) {
            if (name == "inited" && payload != null) LxLog.inited(payload)
            if (name == "updateAlert") LxLog.add("音源提示: $payload")
        }

        @JavascriptInterface
        fun nativeRequest(id: String?, url: String?, method: String?, headersJson: String?, bodyB64: String?, timeoutMs: Int) {
            if (id == null || url == null) return
            executeRequest(id, url, method ?: "GET", headersJson ?: "{}", bodyB64 ?: "", timeoutMs)
        }
    }

    // 必须和 JS 里的 window.__lxCallResult 对应
    private val callResultBridge = object {
        @JavascriptInterface
        fun onResult(callId: String?, payload: String?) {
            val deferred = calls.remove(callId) ?: return
            val obj = runCatching { json.parseToJsonElement(payload ?: "{}").jsonObject }.getOrElse {
                buildJsonObject {
                    put("ok", false)
                    put("error", "音源返回了无法解析的结果")
                }
            }
            deferred.complete(obj)
        }
    }

    /* ---------------- WebView 生命周期 ---------------- */

    private suspend fun ensurePage(context: Context) = withContext(Dispatchers.Main) {
        val existing = webView
        if (existing != null) {
            pageReady?.await()
            return@withContext
        }
        val ready = CompletableDeferred<Unit>()
        pageReady = ready
        val view = runCatching { WebView(context.applicationContext) }.getOrElse {
            pageReady = null
            throw IllegalStateException("系统 WebView 不可用，无法运行动态音源")
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        view.setBackgroundColor(0)
        view.addJavascriptInterface(bridge, "YueYinBridge")
        view.addJavascriptInterface(callResultBridge, "YueYinCall")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                // 页面脚本末尾会调 nativeReady；这里再兜一次，防止脚本先执行完而回调丢失
                ready.complete(Unit)
            }
        }
        webView = view
        view.loadUrl("file:///android_asset/lx/runtime.html")
        try {
            withTimeout(8_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            LxLog.add("运行时页面加载超时")
        }
    }

    /** 加载（或切换）音源脚本。返回是否成功注册了 request 事件。 */
    suspend fun loadScript(fileName: String, script: String): Boolean {
        ensurePage(appContext)
        val key = fileName + ":" + script.hashCode()
        if (loadedKey == key) return true
        val result = evalOnMain(
            "__lxLoadScript(${quote(fileName)}, ${quote(script)})",
        ) ?: return false
        val obj = runCatching { json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return false
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        if (!ok) {
            LxLog.add("脚本加载失败: " + (obj["error"]?.jsonPrimitive?.contentOrNull ?: ""))
            return false
        }
        val hasRequest = (obj["hasRequest"]?.jsonPrimitive?.contentOrNull ?: obj["hasRequest"]?.toString()) == "true"
        if (hasRequest) {
            loadedKey = key
            // 有些音源是「异步初始化」：脚本先发一次版本检查请求，拿到结果后才 send(inited)。
            // 加载完立刻问 declaredPlatforms() 会拿到空集，平台页就会显示成「这个音源没有可用平台」。
            // 这里最多等 2.5 秒，等它把 inited 发出来。
            if (declaredPlatforms().isEmpty()) {
                withTimeoutOrNull(2_500) {
                    while (declaredPlatforms().isEmpty()) delay(120)
                }
            }
        }
        return hasRequest
    }

    /** 音源声明支持的平台（inited 事件里的 sources 键名）。 */
    suspend fun declaredPlatforms(): Set<String> {
        val raw = evalOnMain("__lxDeclared()") ?: return emptySet()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptySet()
        val sources = obj["sources"]?.jsonObject ?: return emptySet()
        return sources.keys
    }

    /**
     * 调用脚本的 request 事件。
     * @return 脚本返回的原始值（通常是 url 字符串或 {url, ...}）
     */
    suspend fun call(source: String, action: String, info: JsonObject): String? {
        val callId = "c" + (++callSeq)
        val deferred = CompletableDeferred<JsonObject>()
        calls[callId] = deferred
        val payload = json.encodeToString(JsonObject.serializer(), info)
        val result = try {
            // 12 秒是一条合理上界：正常取链 0.2~2 秒（实测酷我 0.25s、网易 0.3s、酷狗 0.4s、QQ 0.9s）。
            // 超过这个时间的多半是「版权受限的 VIP 曲目」——音源脚本会把五组 API 全部试一遍才放弃，
            // 实测能跑到 18 秒。原来给 35 秒，等于让一首取不到的歌把整个取链队列（单个 WebView，串行）
            // 堵住半分钟，用户看到的就是「点歌没反应」。
            withTimeout(12_000) {
                evalOnMain(
                    "__lxCall(${quote(callId)}, ${quote(source)}, ${quote(action)}, ${quote(payload)})",
                )
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            calls.remove(callId)
            LxLog.add("音源响应超时（$action/$source）")
            return null
        }
        val ok = result["ok"]?.toString()?.toBoolean() ?: false
        if (!ok) {
            val err = result["error"]?.jsonPrimitive?.contentOrNull
            if (!err.isNullOrBlank()) LxLog.add("音源报错: $err")
            return null
        }
        val value = result["value"] ?: return null
        return when {
            value is JsonPrimitive -> value.contentOrNull
            else -> value.toString()
        }
    }

    /* ---------------- 内部 ---------------- */

    private suspend fun evalOnMain(script: String): String? = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { value ->
                val text = when {
                    value == null || value == "null" -> null
                    value.startsWith("\"") && value.endsWith("\"") -> unquote(value)
                    else -> value
                }
                if (cont.isActive) cont.resume(text)
            }
        }
    }

    private fun executeRequest(id: String, url: String, method: String, headersJson: String, bodyB64: String, timeoutMs: Int) {
        val headers = runCatching {
            val obj = json.parseToJsonElement(headersJson).jsonObject
            val builder = Headers.Builder()
            obj.forEach { (k, v) -> builder.add(k, v.jsonPrimitive.contentOrNull ?: "") }
            builder.build()
        }.getOrElse { Headers.Builder().build() }

        val bodyBytes = if (bodyB64.isBlank()) ByteArray(0) else runCatching {
            Base64.decode(bodyB64, Base64.DEFAULT)
        }.getOrElse { ByteArray(0) }

        val request = runCatching {
            val builder = Request.Builder().url(url).headers(headers)
            when (method.uppercase()) {
                "POST", "PUT", "PATCH" -> {
                    val type = headers["Content-Type"]?.toMediaTypeOrNull()
                        ?: "application/x-www-form-urlencoded".toMediaTypeOrNull()
                    builder.method(method.uppercase(), bodyBytes.toRequestBody(type))
                }
                "HEAD" -> builder.head()
                else -> builder.get()
            }
            builder.build()
        }.getOrElse {
            deliverError(id, "无效的请求地址: $url", 0)
            return
        }

        val effectiveClient = if (timeoutMs in 1..60_000) {
            client.newBuilder().readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
        } else client

        effectiveClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                deliverError(id, e.message ?: "网络请求失败", 0)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { r ->
                    val bytes = runCatching { r.body?.bytes() ?: ByteArray(0) }.getOrElse { ByteArray(0) }
                    val headerJson = buildJsonObject {
                        r.headers.names().forEach { name ->
                            put(name, r.headers.values(name).joinToString(", "))
                        }
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    runOnMain {
                        webView?.evaluateJavascript(
                            "window.__lxResponse(${quote(id)}, ${r.code}, ${quote(headerJson.toString())}, ${quote(b64)})",
                            null,
                        )
                    }
                }
            }
        })
    }

    private fun deliverError(id: String, message: String, status: Int) {
        runOnMain {
            webView?.evaluateJavascript(
                "window.__lxResponseError(${quote(id)}, ${quote(message)}, $status)",
                null,
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun unquote(value: String): String {
        val inner = value.substring(1, value.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < inner.length) {
                            val hex = inner.substring(i + 2, i + 6)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                            i += 6
                        } else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    fun destroy() {
        webView?.let {
            it.removeJavascriptInterface("YueYinBridge")
            it.destroy()
        }
        webView = null
    }

    companion object {
        @Volatile
        private var instance: OnlineSourceRuntime? = null

        fun get(context: Context): OnlineSourceRuntime =
            instance ?: synchronized(this) {
                instance ?: OnlineSourceRuntime(context.applicationContext).also { instance = it }
            }
    }
}

/** 运行时日志：只保留最近若干条，用于把失败原因显示给用户。 */
object LxLog {
    private val lines = ArrayDeque<String>()
    @Volatile
    var inited: String? = null
        private set

    fun add(line: String) {
        synchronized(lines) {
            lines.addLast(line.take(300))
            while (lines.size > 60) lines.removeFirst()
        }
    }

    fun inited(payload: String) {
        inited = payload
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
