package com.yueyin.tv.core.data.source

import kotlinx.serialization.Serializable

/**
 * 一个用户自己导入的 LX 音源脚本。
 *
 * 设计上有两条硬约束：
 * 1. 脚本全文随条目一起存（用户随手删掉电脑上的文件也不会失效），所以有 [MAX_SCRIPT_BYTES] 上限；
 * 2. 能力（[platforms]）只由「检测」写入，不在这里猜——脚本自报的 sources 和真正能出声音是两件事。
 */
@Serializable
data class MusicSource(
    val id: String,
    /** 导入时的文件名，仅用于展示和排错。 */
    val fileName: String,
    val script: String,
    val addedAt: Long = 0L,
    val enabled: Boolean = true,
    /** 检测通过的平台标识（lx 协议里的 source key，如 kw / tx / wy）。 */
    val platforms: List<String> = emptyList(),
    val lastCheckAt: Long = 0L,
    /** 检测结论的一句话摘要，直接显示在列表里。 */
    val lastCheckNote: String = "",
    /** 脚本声明支持、但检测没过的平台，用来解释「为什么只有两个能播」。 */
    val declaredPlatforms: List<String> = emptyList(),
) {
    /**
     * 能不能用只取决于「脚本已保存且没停用」。
     *
     * 检测结果（[platforms]）**不作为门槛**：取链器只按 `enabled` 挑脚本，
     * `platforms` 只用来给候选脚本打分排序。以前这里要求 `platforms` 非空，
     * 等于逼用户先点一次「检测」才能用——那是纯 UI 造出来的假前置步骤。
     */
    val usable: Boolean get() = enabled

    fun summary(): String = when {
        !enabled -> "已停用"
        platforms.isNotEmpty() -> platforms.joinToString(" / ") { sourceLabel(it) } + " 可用"
        // 还没检测过：脚本已经能用，只是还不知道它支持哪几家，别把它说成"不可用"
        lastCheckAt == 0L -> "已就绪"
        else -> lastCheckNote.ifBlank { "已就绪" }
    }

    companion object {
        /** 单个脚本上限 2 MB：正常音源 10~40 KB，超过这个量级基本不是脚本。 */
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

        /** lx 协议的 source key → 显示名。 */
        fun sourceLabel(key: String): String = when (key.lowercase()) {
            "kw" -> "酷我"
            "tx" -> "QQ"
            "wy" -> "网易"
            "kg" -> "酷狗"
            "mg" -> "咪咕"
            else -> key
        }
    }
}

/** 页面用的汇总：有几个源、能覆盖哪些平台。 */
data class MusicSourcesState(
    val sources: List<MusicSource> = emptyList(),
) {
    val count: Int get() = sources.size
    val usableCount: Int get() = sources.count { it.enabled }

    /** 所有源合起来能用的平台（去重、按固定顺序）。没检测过的源贡献不了平台，但仍然是可用的。 */
    val platforms: List<String>
        get() = sources.filter { it.enabled }
            .flatMap { it.platforms }
            .distinct()
            .sortedBy { ORDER.indexOf(it.lowercase()).let { i -> if (i < 0) 99 else i } }

    val hasUsable: Boolean get() = sources.any { it.enabled }

    /** 卡片上那一行状态文案。 */
    fun summaryText(): String = when {
        count == 0 -> "还没添加"
        usableCount == 0 -> "已添加 $count 个 · 已全部停用"
        platforms.isEmpty() -> "已添加 $count 个 · 已就绪"
        else -> "已添加 $count 个 · " + platforms.joinToString(" / ") { MusicSource.sourceLabel(it) } + " 可用"
    }

    private companion object {
        val ORDER = listOf("kw", "tx", "wy", "kg", "mg")
    }
}
