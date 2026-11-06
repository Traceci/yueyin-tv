package com.yueyin.tv.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yueyin.tv.core.data.online.OnlinePlatform
import com.yueyin.tv.ui.source.MusicSourceRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 脚本聚合搜索的端到端验证（真 WebView、真脚本）。
 *
 * 覆盖这条链路：`OnlineScriptSearch.search` → 选脚本 → `OnlineSourceRuntime.loadScript`
 * → 脚本 `request` 事件的 `search` 分支 → 结果 JSON → `parseResult` → `OnlineSong`。
 *
 * 用一份假脚本而不是真音源：真音源要联网，结果不稳定；假脚本能把"字段怎么解析"
 * 逐项钉死（时长 `03:20`、`hash`、`albumId`、`sourceName` 标记），这些正是播放和去重依赖的字段。
 */
@RunWith(AndroidJUnit4::class)
class OnlineScriptSearchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fakeFileName = "yueyin-test-fake-source.js"

    /** 测试期间被关掉的真实脚本，跑完要按原样恢复，别弄脏用户数据。 */
    private var disabled: List<String> = emptyList()

    private companion object {
        val FAKE_SCRIPT = """
            lx.send('inited', {
              sources: {
                kw: { name: '假音源', actions: ['musicUrl', 'search'], qualitys: ['320k', '128k'] }
              }
            })
            lx.on('request', function (req) {
              var action = req.action
              var info = req.info || {}
              if (action !== 'search') return null
              var kw = info.keyword || (info.musicInfo && info.musicInfo.keyword) || ''
              return [
                {
                  songmid: 'fake-1',
                  name: '假搜索结果一 ' + kw,
                  singer: '测试歌手',
                  albumName: '测试专辑',
                  albumId: 'alb-1',
                  interval: '03:20',
                  hash: 'h-1',
                  img: 'https://example.com/a.jpg'
                },
                {
                  songmid: 'fake-2',
                  name: '假搜索结果二 ' + kw,
                  singer: '测试歌手二',
                  albumName: '测试专辑二',
                  albumId: 'alb-2',
                  interval: 210
                }
              ]
            })
        """.trimIndent()
    }

    @Before
    fun setUp() {
        MusicSourceRuntime.init(context)
        val store = MusicSourceRuntime.store
        // 真音源可能在别的平台先返回结果，那样假脚本就轮不到、测试会变得不确定。
        // 这里把它们临时关掉，跑完恢复。
        disabled = store.state.value.sources.filter { it.enabled }.map { it.id }
        disabled.forEach { store.setEnabled(it, false) }
        store.add(fakeFileName, FAKE_SCRIPT)
    }

    @After
    fun tearDown() {
        val store = MusicSourceRuntime.store
        store.state.value.sources
            .filter { it.fileName.equals(fakeFileName, ignoreCase = true) }
            .forEach { store.remove(it.id) }
        disabled.forEach { store.setEnabled(it, true) }
    }

    @Test
    fun searchAggregatesSongsFromImportedScript() = runBlocking {
        val songs = OnlineScriptSearch(context).search("测试关键词")

        assertEquals("假脚本声明了一个平台（kw），应只返回该平台的 2 首歌", 2, songs.size)

        val first = songs[0]
        // 平台：脚本只声明了 kw，聚合结果必须落在酷我上，否则取链时会找错脚本。
        assertEquals(OnlinePlatform.Kuwo, first.platform)
        assertEquals("假搜索结果一 测试关键词", first.name)
        assertEquals("测试歌手", first.artist)
        assertEquals("测试专辑", first.album)
        assertEquals("alb-1", first.albumId)
        assertEquals("h-1", first.mediaId)
        // "03:20" 要换算成秒；换算错会让列表上的时长全部显示成 0:00。
        assertEquals(200, first.durationSeconds)
        // 必须打上来源标记：取链时靠它优先选回同一份脚本（id 编码各家不一样）。
        assertEquals(fakeFileName, first.sourceName)

        val second = songs[1]
        assertEquals(210, second.durationSeconds)
        assertEquals("fake-2", second.id)
    }

    @Test
    fun searchIsCaseInsensitiveCached() = runBlocking {
        val searcher = OnlineScriptSearch(context)
        val first = searcher.search("Love")
        val again = searcher.search("love")
        assertTrue("第一次搜索应拿到结果", first.isNotEmpty())
        // 输入即搜会连打同一个词，缓存命中后必须返回同一份结果（列表不闪）。
        assertEquals(first.map { it.key }, again.map { it.key })
    }
}
