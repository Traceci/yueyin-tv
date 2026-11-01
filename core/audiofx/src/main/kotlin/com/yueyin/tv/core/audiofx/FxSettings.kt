package com.yueyin.tv.core.audiofx

/** 十段均衡器的中心频率（Hz）。与酷狗"蝰蛇音效"的图形均衡器同一套划分。 */
object FxBands {
    val CENTER_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    val LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val COUNT = 10

    /** 单段可调范围（dB）。±12 是听感上"明显但不失真"的边界。 */
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f

    fun clampGain(db: Float): Float = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
}

/**
 * 一套音效参数。
 *
 * 之所以把"十段增益 + 低音 + 声场宽度 + 夜间模式"拆成可独立调节的字段而不是一个整包预设，
 * 是因为电视端的真实用法是「选个预设打底，再按自己电视的喇叭微调」——预设只是起点。
 */
data class FxSettings(
    val enabled: Boolean = false,
    val presetId: String = FxPreset.FLAT.id,
    val gainsDb: List<Float> = FxPreset.FLAT.gainsDb,
    val bassDb: Float = 0f,
    val stereoWidth: Float = 1f,
    val nightMode: Boolean = false,
    val limiterEnabled: Boolean = true,
) {
    fun normalized(): FxSettings = copy(
        gainsDb = if (gainsDb.size == FxBands.COUNT) gainsDb.map(FxBands::clampGain) else FxPreset.FLAT.gainsDb,
        bassDb = bassDb.coerceIn(0f, 12f),
        stereoWidth = stereoWidth.coerceIn(0f, 2f),
    )

    /** 任意一段被改动后就不再是预设本身，标记为自定义，避免下次打开预设被覆盖。 */
    fun withBand(index: Int, gainDb: Float): FxSettings {
        if (index !in 0 until FxBands.COUNT) return this
        val next = gainsDb.toMutableList()
        next[index] = FxBands.clampGain(gainDb)
        return copy(gainsDb = next, presetId = FxPreset.CUSTOM.id, enabled = true)
    }

    /**
     * 选中某个预设。选中「无」= 关掉音效（保留各段参数，切回去还能接着用），
     * 选中其它任意预设 = 立刻开启音效并套用该曲线。
     *
     * 这样「开关」和「选预设」就是同一个动作，界面上不再需要一个单独的开关。
     */
    fun selecting(preset: FxPreset): FxSettings = if (preset == FxPreset.NONE) {
        copy(presetId = FxPreset.NONE.id, enabled = false)
    } else {
        preset.toSettings(this).copy(enabled = true)
    }

    /** 当前生效的音效名（关闭时就是「无」），用于播放条上的按钮文案。 */
    val effectLabel: String
        get() = if (!enabled) FxPreset.NONE.displayName else FxPreset.fromId(presetId).displayName

    companion object {
        val DEFAULT = FxSettings()
    }
}

/**
 * 内置音效预设。
 *
 * 设计依据：电视自带喇叭普遍是「低频薄、1k~3k 发闷、10k 以上几乎没有」，
 * 所以除平直外，每个预设都顺手补了一点 4k/8k，而不是照抄手机上那套曲线。
 *
 * 幅度按「可闻」定：人耳对宽带增益的分辨阈值约 3dB，之前各档最大只差 2dB，
 * 选中之后几乎听不出换了，用户会以为音效没生效。现在特征频段都拉到 4~9dB，
 * 相邻两档之间的差异一眼（一耳）可辨。峰值由链尾的限幅器兜住，不会硬削。
 *
 * [NONE] 是「不处理」这一档，等价于旧版的「音效开关 = 关」。
 * 把它放进预设列表而不是单留一个开关，是因为「选什么都得先开开关」在遥控器上
 * 要按两下、还得记住状态，太反人类。
 */
enum class FxPreset(
    val id: String,
    val displayName: String,
    val gainsDb: List<Float>,
    val bassDb: Float = 0f,
    val stereoWidth: Float = 1f,
    val nightMode: Boolean = false,
) {
    NONE(
        id = "none",
        displayName = "无",
        gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ),
    FLAT(
        id = "flat",
        displayName = "平直",
        gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ),
    POP(
        // 低频托底 + 高频提亮，中低（250~500）让出来避免糊
        id = "pop",
        displayName = "流行",
        gainsDb = listOf(4.5f, 4f, 1.5f, -1.5f, -1f, 1f, 3f, 4f, 4f, 3f),
        bassDb = 3.5f,
    ),
    VOCAL(
        // 31~125 压下去，500~2k 抬起来：人声立刻从伴奏里站出来
        id = "vocal",
        displayName = "人声",
        gainsDb = listOf(-5f, -4f, -2.5f, 0f, 2.5f, 4.5f, 5f, 3.5f, 2f, 0f),
    ),
    BASS(
        id = "bass",
        displayName = "低音",
        gainsDb = listOf(8f, 7f, 5f, 2f, 0f, -2f, -1f, 1.5f, 2f, 1.5f),
        bassDb = 5f,
    ),
    ROCK(
        // 典型 V 型：两头翘、中频挖空
        id = "rock",
        displayName = "摇滚",
        gainsDb = listOf(7f, 5f, -1.5f, -3f, -2f, 1.5f, 4f, 5.5f, 4.5f, 3f),
        bassDb = 3f,
    ),
    CLASSICAL(
        // 均衡为主，靠声场宽度拉开层次
        id = "classical",
        displayName = "古典",
        gainsDb = listOf(3.5f, 2.5f, 1f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3.5f, 3f),
        stereoWidth = 1.3f,
    ),
    NIGHT(
        // 主要靠压缩把动态压平（小音量下也听得清对白），EQ 只做轻微补偿
        id = "night",
        displayName = "深夜",
        gainsDb = listOf(4f, 3f, 1f, 0f, 1f, 2f, 2.5f, 2.5f, 1.5f, 1f),
        nightMode = true,
    ),
    CUSTOM(
        id = "custom",
        displayName = "自定义",
        // 不能写 FxPreset.FLAT.gainsDb：枚举常量初始化时 Companion 还没构造好
        gainsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    ),
    ;

    fun toSettings(base: FxSettings = FxSettings.DEFAULT): FxSettings = base.copy(
        presetId = id,
        gainsDb = gainsDb,
        bassDb = bassDb,
        stereoWidth = stereoWidth,
        nightMode = nightMode,
    )

    companion object {
        fun fromId(id: String): FxPreset = entries.firstOrNull { it.id == id } ?: FLAT

        /** 给 UI 的列表，自定义排在最后、且不进"一键预设"轮转。 */
        val selectable: List<FxPreset> = entries.filter { it != CUSTOM }
    }
}
