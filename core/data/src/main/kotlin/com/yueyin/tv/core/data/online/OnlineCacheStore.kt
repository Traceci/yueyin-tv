// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import android.util.Log
import java.io.File
import java.io.IOException
import org.json.JSONObject

/**
 * 在线内容的磁盘缓存底座（一个 key 一个文件）。
 *
 * 为什么需要它：
 * - **冷启动观感**。在线平台的榜单 / 新歌 / 新碟 / 歌单以前只在内存里缓存，
 *   进程一退就全没，所以每次打开 App 都要把四五个板块重新请求一遍网络，
 *   用户看到的就是「退出再进又全部重新连接读取」。
 * - **恢复队列要能重取链**。音源脚本取链依赖一首完整歌曲对象（酷狗要 hash、QQ 要 songmid），
 *   而这些字段只存在于内存索引里。进程一退就丢了，恢复出来的队列于是永远取不到链。
 *
 * 只缓存「可以过期重取」的东西：榜单列表、歌曲元数据。绝不缓存直链
 * （直链带签名，落盘必然是死链，见 [com.yueyin.tv.core.model.stream.StreamUrlCache] 的注释）。
 *
 * 所有读写都吞异常：缓存失败只能让 App 慢一点，绝不能让功能挂掉。
 */
object OnlineCacheStore {

    private const val TAG = "YueyinOnlineCache"

    @Volatile
    private var root: File? = null

    /** 由 App 层在启动时注入（core:data 拿不到 Context，也就不反向依赖 app）。 */
    fun init(directory: File) {
        root = directory
        if (!directory.isDirectory) directory.mkdirs()
    }

    fun read(key: String): CachedEntry? {
        val file = fileOf(key) ?: return null
        return runCatching {
            if (!file.isFile) return null
            val root = JSONObject(file.readText())
            val payload = root.optString("d")
            if (payload.isBlank()) return null
            CachedEntry(payload, root.optLong("t", 0L))
        }.getOrNull()
    }

    fun write(key: String, payload: String) {
        val file = fileOf(key) ?: return
        runCatching {
            val wrapper = JSONObject().put("t", System.currentTimeMillis()).put("d", payload)
            file.writeText(wrapper.toString())
        }.onFailure { cause ->
            // 磁盘满 / 权限问题都不该影响功能，打一条日志就够。
            if (cause !is IOException) Log.w(TAG, "写入缓存失败 key=$key", cause)
        }
    }

    fun clear() {
        val dir = root ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun fileOf(key: String): File? {
        val dir = root ?: return null
        if (!dir.isDirectory) dir.mkdirs()
        // key 里含 ':' '/' 等字符，直接用会造出子目录或非法文件名：统一取哈希做文件名。
        val name = key.hashCode().toUInt().toString(16) + "-" + key.length
        return File(dir, "$name.json")
    }

    data class CachedEntry(val payload: String, val atMillis: Long)
}
