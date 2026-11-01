package com.yueyin.tv.core.data.source

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电视端的一次性收件服务：局域网里的电脑/手机用浏览器打开，把 .js 拖进来。
 *
 * 只在这一页打开时监听，退出即关（[stop]）。不常驻、不开机自启——这是安全底线。
 * 协议刻意保持极简：
 *   GET  /                 上传页（内嵌 HTML，无外部资源，断网也能开）
 *   POST /upload           body 为脚本原文，文件名放在 X-File-Name（URL 编码）
 *   GET  /ping            给页面做连接探活
 * 不做 multipart：页面用 fetch 直接发原文，省掉解析边界字符串的一堆坑。
 */
class SourceReceiveServer(
    /** 收到并通过静态体检的脚本；返回 false 表示落盘失败（超限等），页面会提示原因。 */
    private val onStored: (fileName: String, script: String) -> Boolean,
) {
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 实际监听端口（8899 被占用就往后找）。 */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var clientAddress: String = ""
        private set

    fun start(): Int {
        if (running.get()) return port
        var candidate = FIRST_PORT
        while (candidate < FIRST_PORT + PORT_TRIES) {
            try {
                socket = ServerSocket(candidate).apply { reuseAddress = true }
                break
            } catch (_: IOException) {
                candidate++
            }
        }
        val server = socket ?: throw IOException("no free port")
        port = candidate
        running.set(true)
        acceptThread = Thread({ acceptLoop(server) }, "yueyin-source-recv").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        acceptThread = null
        clientAddress = ""
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            try {
                handle(client)
            } catch (_: Exception) {
                // 单个连接出错不能拖垮服务
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        clientAddress = client.inetAddress?.hostAddress.orEmpty()

        when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                respond(client, 200, "text/html; charset=utf-8", UPLOAD_PAGE.toByteArray(StandardCharsets.UTF_8))
            }
            method == "GET" && path == "/ping" -> {
                respond(client, 200, "application/json", """{"ok":true}""".toByteArray())
            }
            method == "POST" && path == "/upload" -> handleUpload(client, input, headers)
            else -> respond(client, 404, "text/plain; charset=utf-8", "not found".toByteArray())
        }
    }

    private fun handleUpload(client: Socket, input: InputStream, headers: Map<String, String>) {
        val rawName = headers["x-file-name"].orEmpty()
        val fileName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        val length = headers["content-length"]?.toIntOrNull() ?: -1
        val reject: (String) -> Unit = { reason ->
            respond(client, 200, "application/json", """{"ok":false,"message":"$reason"}""".toByteArray())
        }
        if (fileName.isBlank()) return reject("缺少文件名")
        if (!fileName.lowercase().endsWith(".js")) return reject("只接受 .js 文件")
        if (length <= 0) return reject("没有收到内容")
        if (length > MusicSource.MAX_SCRIPT_BYTES) return reject("文件超过 2 MB")

        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        if (read != length) return reject("传输中断")

        val text = String(buffer, StandardCharsets.UTF_8)
        val stored = SourceScriptValidator.validate(text)
        if (stored != null) return reject(stored)
        if (!onStored(fileName, text)) return reject("保存失败，文件可能过大")

        respond(
            client,
            200,
            "application/json",
            """{"ok":true,"name":"${fileName.replace("\"", "")}"}""".toByteArray(),
        )
    }

    private fun respond(client: Socket, code: Int, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    companion object {
        private const val FIRST_PORT = 8899
        private const val PORT_TRIES = 12

        /**
         * 电视在局域网里的 IPv4 地址。
         *
         * 逐块 try/catch：某些虚拟网卡（dummy0 / p2p0）在枚举时会抛 SocketException，
         * 之前整段包一个 runCatching，一张坏网卡就让整个函数返回 null，
         * 于是页面上永远显示"拿不到局域网地址"。也不再用 isUp 过滤——它同样会抛。
         */
        fun localIpv4(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            var fallback: String? = null
            for (nif in interfaces) {
                val addresses = runCatching { nif.inetAddresses }.getOrNull() ?: continue
                for (address in addresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (address.isSiteLocalAddress) return host
                    if (fallback == null) fallback = host
                }
            }
            return fallback
        }

        private val UPLOAD_PAGE = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>给电视添加音乐源</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0B0F15;color:#F2F4F8;font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
 padding:40px 28px 60px}
.wrap{max-width:720px;margin:0 auto}
h1{font-size:22px;font-weight:700}
p.sub{font-size:13px;color:#8792A2;margin-top:8px;line-height:1.8}
.drop{margin-top:22px;height:190px;border-radius:16px;border:2px dashed rgba(95,200,255,.35);
 background:rgba(95,200,255,.05);display:flex;flex-direction:column;align-items:center;
 justify-content:center;gap:8px;cursor:pointer;transition:.15s}
.drop.hot{background:rgba(95,200,255,.12);border-color:#5FC8FF}
.drop .d1{font-size:17px;font-weight:600}
.drop .d2{font-size:12.5px;color:#8792A2}
.list{margin-top:20px}
.row{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:9px;
 background:rgba(255,255,255,.03);margin-bottom:7px;font-size:13.5px}
.row .st{margin-left:auto;font-size:12px;color:#4FD1A5}
.row .st.bad{color:#FF7A5C}
.dot{width:7px;height:7px;border-radius:50%;background:#4FD1A5}
.dot.bad{background:#FF7A5C}
.foot{margin-top:26px;font-size:12px;color:#6F7A88;line-height:1.9}
</style></head><body><div class="wrap">
<h1>给电视添加音乐源</h1>
<p class="sub">把 LX 音源脚本（.js）拖到下面，或者点一下选择文件。传完电视那边会自动出现，不用再操作电视。</p>
<div class="drop" id="drop">
  <div class="d1">把 .js 文件拖到这里</div>
  <div class="d2">或者点击选择 · 支持多选 · 单个不超过 2 MB</div>
</div>
<input id="file" type="file" accept=".js" multiple hidden>
<div class="list" id="list"></div>
<div class="foot">只在这一页打开时接收，电视端退出即关闭。<br>脚本只在电视本地保存，不会上传到任何服务器。</div>
</div>
<script>
const drop=document.getElementById('drop'),file=document.getElementById('file'),list=document.getElementById('list');
drop.onclick=()=>file.click();
['dragenter','dragover'].forEach(t=>drop.addEventListener(t,e=>{e.preventDefault();drop.classList.add('hot')}));
['dragleave','drop'].forEach(t=>drop.addEventListener(t,e=>{e.preventDefault();drop.classList.remove('hot')}));
drop.addEventListener('drop',e=>{upload([...e.dataTransfer.files])});
file.onchange=()=>upload([...file.files]);
async function upload(files){
  for(const f of files){
    const row=document.createElement('div');row.className='row';
    row.innerHTML='<span class="dot"></span><span class="nm">'+f.name+'</span><span class="st">上传中…</span>';
    list.prepend(row);
    const st=row.querySelector('.st'),dot=row.querySelector('.dot');
    try{
      const res=await fetch('/upload',{method:'POST',body:await f.text(),
        headers:{'X-File-Name':encodeURIComponent(f.name)}});
      const j=await res.json();
      if(j.ok){st.textContent='✓ 已接收';}
      else{st.textContent=j.message||'失败';st.classList.add('bad');dot.classList.add('bad');}
    }catch(err){st.textContent='网络错误';st.classList.add('bad');dot.classList.add('bad');}
  }
}
</script></body></html>
""".trimIndent()
    }
}

/** 脚本的静态体检：只拦明显不是 LX 音源的东西，真正的可用性交给运行时检测。 */
object SourceScriptValidator {
    /**
     * 注意：**不能**要求出现字面量 `lx.on(` / `lx.send(`。
     * 现成的音源几乎都过了 JS 混淆器，调用被拆成 `lx["on"]` 或字符串数组下标，
     * 按字面量卡会把能用的脚本全部拒掉（用户看到的就是"传上去说不是音源"）。
     * 这里只做「像不像一段 JS」的最低限度判断，能不能出歌由列表页的「检测」决定。
     */
    private val JS_MARKERS = listOf(
        "function", "=>", "const ", "let ", "var ",
        "lx.", "lx[", "@name", "@description", "module.exports",
    )

    /** 返回 null 表示通过，否则返回给用户看的原因。 */
    fun validate(script: String): String? {
        if (script.isBlank()) return "文件是空的"
        val head = script.trimStart()
        if (head.startsWith("<")) return "这不像是 JS 文件（开头是尖括号，可能是网页）"
        if (!JS_MARKERS.any { script.contains(it) }) return "这不像是 JS 脚本"
        if (Regex("""require\s*\(\s*['"]fs['"]""").containsMatchIn(script)) {
            return "脚本会读写本地文件，已拒绝"
        }
        return null
    }
}
