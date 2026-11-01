// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.data.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只测清洗函数：它们是纯字符串变换，跟网络无关，
 * 但线上表现全压在这几行上（酷我给过来的字段脏得比较有想象力）。
 */
class OnlineMusicApiTest {
    @Test
    fun `酷我歌手名里的 nbsp 是空格不是分隔符`() {
        // 真实返回：Disney&nbsp;Peaceful&nbsp;Guitar\\u0026Disney
        val raw = "Disney&nbsp;Peaceful&nbsp;Guitar" + "\\\\" + "u0026Disney"
        assertEquals("Disney Peaceful Guitar / Disney", OnlineMusicApi.cleanArtist(raw))
    }

    @Test
    fun `单个反斜杠的 unicode 转义也能还原`() {
        val raw = "A" + "\\" + "u0026B"
        assertEquals("A / B", OnlineMusicApi.cleanArtist(raw))
    }

    @Test
    fun `中文歌手名原样保留`() {
        assertEquals("周杰伦", OnlineMusicApi.cleanArtist("周杰伦"))
        assertEquals("周杰伦 / 费玉清", OnlineMusicApi.cleanArtist("周杰伦&nbsp;&amp;&nbsp;费玉清"))
    }

    @Test
    fun `专辑名里的 nbsp 变成空格`() {
        assertEquals(
            "Disney Lieder zum Einschlafen",
            OnlineMusicApi.cleanHtml("Disney&nbsp;Lieder&nbsp;zum&nbsp;Einschlafen"),
        )
    }

    @Test
    fun `歌名里的 HTML 标签被去掉`() {
        assertEquals("晴天", OnlineMusicApi.cleanHtml("<em>晴天</em>"))
    }
}
