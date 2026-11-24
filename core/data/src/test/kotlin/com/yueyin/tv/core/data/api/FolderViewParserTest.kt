package com.yueyin.tv.core.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用飞牛 folder-view/list 的真实响应结构锁定解析行为（data.folder.folders/tracks）。 */
class FolderViewParserTest {

    // 取自实机 NAS 的响应，裁剪为 2 个文件夹 + 2 首歌曲。
    private val sample = """
        {"code":0,"msg":"","data":{"folder":{
          "name":"音乐","path":"/vol3/1000/Downloads/音乐","itemCount":26154,
          "folders":[
            {"name":"放空灵魂：光而不耀","path":"/vol3/1000/Downloads/音乐/放空灵魂：光而不耀","itemCount":97,"isAdminDeleted":false},
            {"name":"时光音乐会","path":"/vol3/1000/Downloads/音乐/时光音乐会","itemCount":40,"isAdminDeleted":false}
          ],
          "tracks":[
            {"guid":"9e63d5869a58442b8ddc474151e679f6","title":"不疼不爱","duration":219298,"isCue":false,
             "coverId":"track_1314040a4f234e5ab1e4649f2cfa9884",
             "album":{"name":"不疼不爱"},
             "artists":[{"guid":"2e69dd294b0740a886f94b6020f06ba1","name":"田园"}],
             "audioSpec":{"format":"flac","path":"/vol3/1000/Downloads/音乐/不疼不爱 - 田园.flac","size":23186965},
             "isFavorite":false,"isAdminDeleted":false},
            {"guid":"68767ad97b034b13b3f599c66becd928","title":"大城小爱","duration":220000,"isCue":false,
             "artists":[],"audioSpec":{"format":"mp3","path":"/vol3/1000/Downloads/音乐/大城小爱.mp3"},
             "isFavorite":false,"isAdminDeleted":true},
            {"guid":"fallback-guid","title":"","duration":0,"isCue":false,
             "artists":[],"audioSpec":{"format":"mp3","path":"/vol3/1000/Downloads/音乐/透明 - 梁咏琪.mp3"},
             "isFavorite":false,"isAdminDeleted":false}
          ]},"sort":"name,asc"}}
    """.trimIndent()

    @Test fun `parses folders and tracks mixed and skips admin deleted`() {
        val entries = parseFolderViewList(Json.parseToJsonElement(sample), null)

        // 第 2 首歌 isAdminDeleted=true，应被过滤。
        assertEquals(4, entries.size)
        val folders = entries.filter { it.isFolder }
        val tracks = entries.filter { !it.isFolder }
        assertEquals(2, folders.size)
        assertEquals(2, tracks.size)

        val first = folders.first()
        assertEquals("放空灵魂：光而不耀", first.name)
        assertEquals("/vol3/1000/Downloads/音乐/放空灵魂：光而不耀", first.id)
        assertEquals("97 首", first.subtitle)
        assertEquals(97, first.childCount)
        assertTrue(first.path!!.startsWith("/vol3/"))

        val song = tracks.first()
        assertFalse(song.isFolder)
        assertEquals("9e63d5869a58442b8ddc474151e679f6", song.id)
        assertEquals("不疼不爱", song.name)
        assertEquals("田园", song.subtitle)
        assertEquals("/vol3/1000/Downloads/音乐/不疼不爱 - 田园.flac", song.path)
        assertEquals("track_1314040a4f234e5ab1e4649f2cfa9884", song.coverId)
        assertEquals("田园", song.artistName)
        assertEquals("不疼不爱", song.albumName)
        assertEquals(219298L, song.durationMs)

        val fallback = tracks.last()
        assertEquals("透明", fallback.name)
        assertEquals("梁咏琪", fallback.artistName)
        assertEquals("梁咏琪", fallback.subtitle)
    }

    @Test fun `propagates parent path for sub folder navigation`() {
        val entries = parseFolderViewList(Json.parseToJsonElement(sample), "/vol3/1000/Downloads/音乐")
        assertTrue(entries.all { it.parentId == "/vol3/1000/Downloads/音乐" })
    }

    @Test fun `accepts unwrapped data element returned by ApiDecoder`() {
        val envelope = Json.parseToJsonElement(sample)
        val dataOnly = (envelope as kotlinx.serialization.json.JsonObject)["data"]!!
        val entries = parseFolderViewList(dataOnly, null)
        assertEquals(4, entries.size)
        assertEquals("放空灵魂：光而不耀", entries.first().name)
    }

    @Test fun `folder cover is picked deterministically from contained tracks`() {
        val root = Json.parseToJsonElement(sample)
        val first = parseFolderCover(root, "/vol3/1000/Downloads/音乐/放空灵魂：光而不耀")
        val again = parseFolderCover(root, "/vol3/1000/Downloads/音乐/放空灵魂：光而不耀")

        assertEquals("track_1314040a4f234e5ab1e4649f2cfa9884", first)
        assertEquals(first, again)
        assertTrue(parseFolderCover(Json.parseToJsonElement("""{"data":{"folder":{"folders":[],"tracks":[]}}}"""), "/empty").isNullOrBlank())
    }

    @Test fun `pickCoverId is stable for nested container folders`() {
        val candidates = listOf("cover-a", "cover-b", "cover-c")
        val first = pickCoverId(candidates, "/vol3/1000/Downloads/音乐/时光音乐会")
        val again = pickCoverId(candidates, "/vol3/1000/Downloads/音乐/时光音乐会")

        assertTrue(first in candidates)
        assertEquals(first, again)
        assertTrue(pickCoverId(emptyList(), "/empty").isNullOrBlank())
    }

    @Test fun `returns empty for unexpected envelope`() {
        assertTrue(parseFolderViewList(Json.parseToJsonElement("""{"code":100002,"msg":"invalid arguments","data":null}"""), null).isEmpty())
    }
}
