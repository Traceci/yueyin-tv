package com.yueyin.tv.core.cast

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 局域网中转服务：把「手机能读到的音频」变成「电视能读到的 http 地址」。
 *
 * 为什么必须有它：
 * - 本地 / 文件夹曲目是 `content://`，电视完全读不到；
 * - 协议平台（SMB / WebDAV）走的是 `127.0.0.1` 本机代理，电视同样读不到；
 * - 在线直链常带鉴权头或会过期，交给电视自己取容易 401。
 * 所以统一由手机取流、按字节转发，电视只认 `http://<手机IP>:<port>/m/<token>`。
 *
 * 只监听随机端口、路径带随机 token；服务在停止投播时立刻关闭，不常驻。
 */
class LanMediaServer(private val context: Context) {
    private val sources = ConcurrentHashMap<String, CastSource>()
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    var port: Int = 0
        private set

    fun start(): Boolean {
        if (running) return true
        val socket = runCatching { ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")) }
            .getOrNull() ?: return false
        serverSocket = socket
        port = socket.localPort
        running = true
        thread(name = "yueyin-cast-http", isDaemon = true) { acceptLoop(socket) }
        return true
    }

    fun register(source: CastSource): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        sources[token] = source
        return "/m/$token"
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        sources.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input).orEmpty()
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
                }
            }
            val output = BufferedOutputStream(socket.getOutputStream())
            val source = if (path.startsWith("/m/")) sources[path.removePrefix("/m/")] else null
            if (source == null) {
                writeSimple(output, 404, "Not Found")
                return
            }
            if (source.uri.startsWith("content://")) {
                serveContent(output, method, source, headers)
            } else {
                serveRemote(output, method, source, headers)
            }
            runCatching { output.flush() }
        }
    }

    private fun serveContent(
        out: OutputStream,
        method: String,
        source: CastSource,
        headers: Map<String, String>,
    ) {
        val resolver = context.contentResolver
        val uri = Uri.parse(source.uri)
        val descriptor = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (descriptor == null) {
            writeSimple(out, 404, "Not Found")
            return
        }
        descriptor.use { file ->
            val total = file.length
            val mime = resolver.getType(uri) ?: source.mimeType.ifBlank { "audio/mpeg" }
            val range = parseRange(headers["range"], total)
            if (method == "HEAD") {
                writeHeaders(out, if (range != null) 206 else 200, mime, total, range, total)
                return
            }
            file.createInputStream().use { stream ->
                if (range != null) {
                    skipFully(stream, range.first)
                    writeHeaders(out, 206, mime, range.last - range.first + 1, range, total)
                    copyLimited(stream, out, range.last - range.first + 1)
                } else {
                    writeHeaders(out, 200, mime, total, null, total)
                    stream.copyTo(out, 64 * 1024)
                }
            }
        }
    }

    private fun serveRemote(
        out: OutputStream,
        method: String,
        source: CastSource,
        headers: Map<String, String>,
    ) {
        val connection = (URL(source.uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = if (method == "HEAD") "HEAD" else "GET"
        }
        headers["range"]?.let { connection.setRequestProperty("Range", it) }
        val code = runCatching { connection.responseCode }.getOrNull()
        if (code == null || code >= 400) {
            runCatching { connection.disconnect() }
            writeSimple(out, code?.takeIf { it >= 400 } ?: 502, "Upstream Failed")
            return
        }
        val mime = connection.contentType?.substringBefore(';') ?: source.mimeType
        val length = connection.contentLengthLong
        val contentRange = connection.getHeaderField("Content-Range")
        writeHeaders(out, code, mime, length, null, length, contentRange)
        if (method != "HEAD") {
            runCatching { connection.inputStream.copyTo(out, 64 * 1024) }
        }
        runCatching { connection.disconnect() }
    }

    private fun writeSimple(out: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
    }

    private fun writeHeaders(
        out: OutputStream,
        code: Int,
        mime: String,
        length: Long,
        range: LongRange?,
        total: Long = length,
        contentRange: String? = null,
    ) {
        val statusText = if (code == 206) "Partial Content" else "OK"
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (length >= 0) append("Content-Length: ").append(length).append("\r\n")
            val rangeHeader = contentRange
                ?: range?.let { "bytes ${it.first}-${it.last}/${if (total >= 0) total else "*"}" }
            if (rangeHeader != null) append("Content-Range: ").append(rangeHeader).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
    }

    /** 只支持单段 Range（`bytes=a-b` / `bytes=a-`）；多段返回 null 走整文件。 */
    private fun parseRange(header: String?, total: Long): LongRange? = parseHttpRange(header, total)

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (stream.read() >= 0) {
                remaining -= 1
            } else {
                return
            }
        }
    }

    private fun copyLimited(stream: InputStream, out: OutputStream, limit: Long) {
        var remaining = limit
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) break
            if (byte != '\r'.code) builder.append(byte.toChar())
            if (builder.length > 8_192) return null
        }
        return builder.toString()
    }

    companion object {
        /** 优先返回 Wi-Fi 网段的 IPv4；没有网络时返回 null，由调用方提示。 */
        fun localIpv4(): String? {
            val candidates = ArrayList<String>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    val wireless = nif.name.lowercase().let {
                        it.startsWith("wlan") || it.startsWith("ap") || it.startsWith("eth")
                    }
                    nif.inetAddresses.toList().forEach { address ->
                        if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                            val host = address.hostAddress ?: return@forEach
                            if (wireless) candidates.add(0, host) else candidates.add(host)
                        }
                    }
                }
            }
            return candidates.firstOrNull { it.isNotBlank() }
        }
    }
}
