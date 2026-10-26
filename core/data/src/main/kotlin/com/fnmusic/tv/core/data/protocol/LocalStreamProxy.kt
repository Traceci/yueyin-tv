package com.fnmusic.tv.core.data.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地回环流代理：把「Media3 / 图片加载器拿不到的东西」变成一个普通 HTTP 地址。
 *
 * 为什么必须有它：
 * - Media3 只会对 `streamUrl` 里的地址发**普通 GET**，无法附加请求头，所以
 *   WebDAV 的 Basic 认证、SMB 这种根本不是 HTTP 的协议都没法直接喂给播放器；
 * - 封面同理（上层只是 `GET coverUrl` 拿字节）。
 *
 * 做法：在 `127.0.0.1` 上起一个极小的 HTTP/1.1 服务，路径里带一个进程级随机 token
 * （防止同机其它 App 猜到地址），路径尾部是上游条目的摘要。吐流时：
 * - [Upstream.Http]：转发 Range 请求头到真实服务器（可注入 Basic / 自定义头）；
 * - [Upstream.Smb]：用 jcifs 打开文件流，按 HTTP Range 手动 seek + 限长。
 *
 * 全部请求都是「一问一答 + 关连接」（`Connection: close`），不做 keep-alive ——
 * 播放器会自己重连，实现简单且没有连接状态可错。
 */
object LocalStreamProxy {

    /** 上游来源。 */
    sealed interface Upstream {
        /** 普通 HTTP 资源，可带认证头。 */
        data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : Upstream

        /** SMB 资源，形如 `smb://192.168.1.10/share/music/a.flac`。 */
        data class Smb(val url: String, val username: String = "", val password: String = "") : Upstream
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val MAX_HEADER_LINES = 64
    private const val BUFFER_SIZE = 64 * 1024

    private val upstreams = ConcurrentHashMap<String, Upstream>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            // 流式响应不设读超时：大文件播放中途停顿不应被代理掐断
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var port: Int = 0
    @Volatile private var token: String = ""

    /** 确保代理已启动；返回是否可用（启动失败不影响登录，只是这些平台播不了）。 */
    fun ensureReady(): Boolean {
        if (port > 0) return true
        synchronized(this) {
            if (port > 0) return true
            return runCatching {
                val server = ServerSocket(0, 32, InetAddress.getByName(LOOPBACK))
                token = java.util.UUID.randomUUID().toString().replace("-", "")
                port = server.localPort
                val pool = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "fn-stream-proxy").apply { isDaemon = true }
                }
                Thread({
                    while (!server.isClosed) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: break
                        pool.execute { handle(socket) }
                    }
                }, "fn-stream-proxy-accept").apply { isDaemon = true }.start()
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 登记一个上游并返回可播放/可下载的本地地址。
     * 相同上游多次调用得到相同地址（按内容摘要去重），避免播放队列里地址雪崩。
     */
    fun urlOf(upstream: Upstream): String {
        if (!ensureReady()) return ""
        val key = keyOf(upstream)
        upstreams[key] = upstream
        return "http://$LOOPBACK:$port/$token/$key"
    }

    // ---------------------------------------------------------------- 请求处理

    private fun keyOf(upstream: Upstream): String {
        val identity = when (upstream) {
            is Upstream.Http -> "http|${upstream.url}|${upstream.headers.toSortedMap()}"
            is Upstream.Smb -> "smb|${upstream.url}|${upstream.username}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { String.format("%02x", it) }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 20_000
                val input = s.getInputStream().buffered()
                val request = readRequest(input) ?: return
                val out = s.getOutputStream().buffered()
                serve(request, out)
                out.flush()
            }
        }
    }

    private data class ProxyRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private fun readRequest(input: InputStream): ProxyRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        var lines = 0
        while (lines++ < MAX_HEADER_LINES) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return ProxyRequest(parts[0].uppercase(), parts[1], headers)
    }

    /** 逐字节读一行：不用 BufferedReader，避免它把后续二进制内容读进缓冲区。 */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) return buffer.toString().trimEnd('\r')
            if (buffer.length > 8 * 1024) return buffer.toString()
            buffer.append(byte.toChar())
        }
    }

    private fun serve(request: ProxyRequest, out: OutputStream) {
        val prefix = "/$token/"
        val path = request.path.substringBefore('?')
        if (!path.startsWith(prefix)) {
            writeHead(out, 403, "Forbidden", emptyList())
            return
        }
        val upstream = upstreams[path.removePrefix(prefix)]
        if (upstream == null) {
            writeHead(out, 404, "Not Found", emptyList())
            return
        }
        when (upstream) {
            is Upstream.Http -> serveHttp(request, upstream, out)
            is Upstream.Smb -> serveSmb(request, upstream, out)
        }
    }

    private fun serveHttp(request: ProxyRequest, upstream: Upstream.Http, out: OutputStream) {
        val builder = Request.Builder().url(upstream.url).get()
        upstream.headers.forEach { (name, value) -> builder.header(name, value) }
        request.headers["range"]?.let { builder.header("Range", it) }
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    writeHead(out, response.code, "Upstream Error", emptyList())
                    return
                }
                val headers = buildList {
                    response.header("Content-Type")?.let { add("Content-Type" to it) }
                    response.header("Content-Length")?.let { add("Content-Length" to it) }
                    response.header("Content-Range")?.let { add("Content-Range" to it) }
                    response.header("Accept-Ranges")?.let { add("Accept-Ranges" to it) }
                    response.header("Last-Modified")?.let { add("Last-Modified" to it) }
                }
                writeHead(
                    out = out,
                    code = response.code,
                    reason = if (response.code == 206) "Partial Content" else "OK",
                    headers = headers,
                )
                if (request.method == "HEAD") return
                response.body?.byteStream()?.use { body -> copyFully(body, out, Long.MAX_VALUE) }
            }
        }.onFailure {
            runCatching { writeHead(out, 502, "Bad Gateway", emptyList()) }
        }
    }

    private fun serveSmb(request: ProxyRequest, upstream: Upstream.Smb, out: OutputStream) {
        runCatching {
            val file = SmbSupport.openFile(upstream.url, upstream.username, upstream.password)
            val total = file.length()
            val range = parseRange(request.headers["range"], total)
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1).coerceAtLeast(0L)
            val length = if (total <= 0) 0L else (end - start + 1).coerceAtLeast(0L)
            val headers = buildList {
                add("Content-Type" to contentTypeOf(upstream.url))
                add("Accept-Ranges" to "bytes")
                add("Content-Length" to length.toString())
                if (range != null) add("Content-Range" to "bytes $start-$end/$total")
            }
            writeHead(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", headers)
            if (request.method == "HEAD" || length <= 0) return
            file.getInputStream().use { input ->
                skipFully(input, start)
                copyFully(input, out, length)
            }
        }.onFailure {
            runCatching { writeHead(out, 502, "Bad Gateway", emptyList()) }
        }
    }

    /** 只支持单区间 `bytes=start-end` / `bytes=start-` / `bytes=-suffix`，足够播放器 seek 用。 */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header.isNullOrBlank() || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return runCatching {
            if (startText.isEmpty()) {
                val suffix = endText.toLong()
                val start = (total - suffix).coerceAtLeast(0L)
                start to (total - 1).coerceAtLeast(0L)
            } else {
                val start = startText.toLong()
                val end = if (endText.isEmpty()) total - 1 else endText.toLong().coerceAtMost(total - 1)
                if (start > end || start >= total) null else start to end
            }
        }.getOrNull()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        builder.append("Connection: close\r\n")
        headers.forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }
        builder.append("\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun copyFully(input: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        out.flush()
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            remaining -= read
        }
    }

    internal fun contentTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        "ape" -> "audio/x-ape"
        "dsf", "dff" -> "audio/x-dsf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "lrc", "txt" -> "text/plain; charset=utf-8"
        else -> "audio/mpeg"
    }
}
