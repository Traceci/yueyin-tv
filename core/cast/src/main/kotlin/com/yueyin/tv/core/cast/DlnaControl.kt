package com.yueyin.tv.core.cast

import java.net.HttpURLConnection
import java.net.URL

/**
 * 最小 SOAP 客户端：只实现投播单曲需要的五个 AVTransport 动作。
 *
 * 不引第三方 UPnP 库——它们动辄几百 KB，而这里要的只是「POST 一段固定格式的 XML」。
 */
internal object DlnaControl {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun setAvTransportUri(device: DlnaDevice, url: String, metadata: String) {
        call(
            device,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>",
        )
    }

    fun play(device: DlnaDevice) {
        call(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun pause(device: DlnaDevice) {
        call(device, "Pause", "<InstanceID>0</InstanceID>")
    }

    fun stop(device: DlnaDevice) {
        call(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    fun seek(device: DlnaDevice, positionMs: Long) {
        call(
            device,
            "Seek",
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                "<Target>${formatTime(positionMs)}</Target>",
        )
    }

    private fun call(device: DlnaDevice, action: String, args: String) {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(AV_TRANSPORT).append("\">")
            append(args)
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val connection = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 6_000
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                throw IllegalStateException("$action 失败（HTTP $code）${detail.take(160)}")
            }
            // 必须把响应读干净，否则连接不会复用/释放。
            runCatching { connection.inputStream?.bufferedReader()?.use { it.readText() } }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}

/** DIDL-Lite 元数据：有些渲染器不看它也能播，但没有它时进度条/曲名会是空的。 */
internal fun buildDidlMetadata(source: CastSource, url: String): String = buildString {
    append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
    append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
    append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
    append("<item id=\"0\" parentID=\"0\" restricted=\"1\">")
    append("<dc:title>").append(escapeXml(source.title)).append("</dc:title>")
    source.artist?.takeIf { it.isNotBlank() }?.let {
        append("<dc:creator>").append(escapeXml(it)).append("</dc:creator>")
        append("<upnp:artist>").append(escapeXml(it)).append("</upnp:artist>")
    }
    source.album?.takeIf { it.isNotBlank() }?.let {
        append("<upnp:album>").append(escapeXml(it)).append("</upnp:album>")
    }
    append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
    append("<res protocolInfo=\"http-get:*:")
        .append(escapeXml(source.mimeType))
        .append(":*\">")
        .append(escapeXml(url))
        .append("</res>")
    append("</item></DIDL-Lite>")
}

internal fun escapeXml(value: String): String = buildString(value.length + 16) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}
