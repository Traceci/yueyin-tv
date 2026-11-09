package com.yueyin.tv.core.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/**
 * SSDP 发现：向 239.255.255.250:1900 发 M-SEARCH，收 MediaRenderer 的应答，
 * 再拉一次设备描述 XML，拿到 AVTransport 的 controlURL。
 *
 * 绑定临时端口而不是 1900：SSDP 的应答是单播回「请求来源端口」的，
 * 用 1900 反而会和系统/其它投屏 App 抢端口，绑定失败就一个设备都搜不到。
 */
internal object DlnaDiscovery {
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ST_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    suspend fun discover(context: Context, timeoutMs: Long = 4_000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DlnaDevice>()
            val multicastLock = runCatching {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("yueyin-dlna")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            val socket = runCatching { DatagramSocket(null).apply { bind(InetSocketAddress(0)) } }
                .getOrNull() ?: return@withContext emptyList()
            try {
                socket.soTimeout = 700
                val packet = searchPacket()
                val target = InetAddress.getByName(SSDP_HOST)
                repeat(3) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, target, SSDP_PORT)) }
                    Thread.sleep(150)
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val seenLocations = HashSet<String>()
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline && found.size < MAX_DEVICES) {
                    val response = DatagramPacket(buffer, buffer.size)
                    val ok = runCatching { socket.receive(response); true }.getOrDefault(false)
                    if (!ok) continue
                    val headers = parseHeaders(String(response.data, 0, response.length))
                    val location = headers["location"] ?: continue
                    val usn = headers["usn"] ?: location
                    if (!seenLocations.add(location)) continue
                    val device = runCatching { fetchDevice(location, usn) }.getOrNull() ?: continue
                    found[usn] = device
                }
            } finally {
                runCatching { socket.close() }
                runCatching { multicastLock?.release() }
            }
            found.values.sortedBy { it.name }
        }

    private const val MAX_DEVICES = 24

    private fun searchPacket(): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(SSDP_HOST).append(':').append(SSDP_PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 3\r\n")
        append("ST: ").append(ST_RENDERER).append("\r\n")
        append("\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun parseHeaders(raw: String): Map<String, String> = raw.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }
        .toMap()

    private fun fetchDevice(location: String, usn: String): DlnaDevice? {
        val connection = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            requestMethod = "GET"
        }
        val xml = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { connection.disconnect() }
        }
        if (xml.isBlank()) return null
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        var friendlyName = ""
        var modelName: String? = null
        var avTransport: String? = null
        var rendering: String? = null
        var inService = false
        var serviceType = ""
        var controlUrl = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "friendlyname" -> if (friendlyName.isBlank()) friendlyName = parser.nextText().trim()
                    "modelname" -> modelName = parser.nextText().trim().takeIf { it.isNotBlank() }
                    "service" -> {
                        inService = true
                        serviceType = ""
                        controlUrl = ""
                    }
                    "servicetype" -> if (inService) serviceType = parser.nextText().trim()
                    "controlurl" -> if (inService) controlUrl = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name?.equals("service", ignoreCase = true) == true) {
                    if (controlUrl.isNotBlank()) {
                        val absolute = runCatching { URL(URL(location), controlUrl).toString() }.getOrNull()
                        if (absolute != null) {
                            when {
                                serviceType.contains("AVTransport", ignoreCase = true) -> avTransport = absolute
                                serviceType.contains("RenderingControl", ignoreCase = true) -> rendering = absolute
                            }
                        }
                    }
                    inService = false
                }
            }
            event = parser.next()
        }
        val control = avTransport ?: return null
        return DlnaDevice(
            usn = usn,
            name = friendlyName.ifBlank { modelName ?: "DLNA 设备" },
            modelName = modelName,
            location = location,
            controlUrl = control,
            renderingControlUrl = rendering,
        )
    }
}
