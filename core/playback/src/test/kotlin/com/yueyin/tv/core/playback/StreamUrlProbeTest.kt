package com.yueyin.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** [StreamUrlProbe] 的分类逻辑：用裸 ServerSocket 手写 HTTP 响应模拟死链/活链。 */
class StreamUrlProbeTest {

    @Test
    fun `audio partial content is OK`() {
        val response = "HTTP/1.1 206 Partial Content\r\n" +
            "Content-Type: audio/mpeg\r\n" +
            "Content-Range: bytes 0-1/1000\r\n" +
            "Content-Length: 2\r\n\r\n\u0001\u0002"
        serveOne(response) { port ->
            assertEquals(StreamUrlProbe.Result.OK, StreamUrlProbe.probe("http://127.0.0.1:$port/song.mp3"))
        }
    }

    @Test
    fun `html page is BAD`() {
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: 5\r\n\r\n<html"
        serveOne(response) { port ->
            assertEquals(StreamUrlProbe.Result.BAD, StreamUrlProbe.probe("http://127.0.0.1:$port/error"))
        }
    }

    @Test
    fun `gone is BAD`() {
        val response = "HTTP/1.1 410 Gone\r\nContent-Length: 0\r\n\r\n"
        serveOne(response) { port ->
            assertEquals(StreamUrlProbe.Result.BAD, StreamUrlProbe.probe("http://127.0.0.1:$port/gone"))
        }
    }

    @Test
    fun `range not satisfiable is OK`() {
        val response = "HTTP/1.1 416 Requested Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n"
        serveOne(response) { port ->
            assertEquals(StreamUrlProbe.Result.OK, StreamUrlProbe.probe("http://127.0.0.1:$port/empty"))
        }
    }

    @Test
    fun `connection refused is UNKNOWN`() {
        // 没有服务监听的端口：连接失败 → 判不了就放行
        assertEquals(StreamUrlProbe.Result.UNKNOWN, StreamUrlProbe.probe("http://127.0.0.1:9/dead"))
    }

    @Test
    fun `non http uri passes without network`() {
        assertEquals(StreamUrlProbe.Result.OK, StreamUrlProbe.probe("content://media/song/1"))
        assertEquals(StreamUrlProbe.Result.OK, StreamUrlProbe.probe("file:///sdcard/a.mp3"))
    }

    /** 起一个只服务一次连接的临时 HTTP 端口，回 [response]，跑完即关。 */
    private inline fun serveOne(response: String, block: (port: Int) -> Unit) {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val thread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    readUntilHeaderEnd(socket)
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            block(server.localPort)
        } finally {
            server.close()
        }
    }

    /** 读到请求头结束（\r\n\r\n）为止；读不到就超时返回，不影响回响应。 */
    private fun readUntilHeaderEnd(socket: Socket) {
        val input = socket.getInputStream()
        val buf = ByteArray(1024)
        var tail = CharArray(0)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) return
                tail = String(buf, 0, n, Charsets.ISO_8859_1).toCharArray() + tail
                if (tail.concatToString().contains("\r\n\r\n")) return
                if (tail.size > 4096) return
            }
        } catch (_: SocketTimeoutException) {
        }
    }
}
