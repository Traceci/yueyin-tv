package com.yueyin.tv.core.data.localmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yueyin.tv.core.data.local.AppDatabase
import com.yueyin.tv.core.model.Album
import com.yueyin.tv.core.model.Artist
import com.yueyin.tv.core.model.CollectionGuid
import com.yueyin.tv.core.model.Page
import com.yueyin.tv.core.model.PlaybackTrack
import com.yueyin.tv.core.model.Track
import com.yueyin.tv.core.model.TrackGuid
import com.yueyin.tv.core.model.TrackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地曲库门面。与在线 / 协议平台完全并行，不参与 ConnectProfileStore.activeId，
 * 因此切换平台不会清空本地曲库。本文件及同包所有实现禁止 import online / source 包。
 */
class LocalMusicLibrary(
    private val context: Context,
    database: AppDatabase,
) {
    private val dao = database.localMusicDao()
    private val folderDao = database.localFolderDao()

    /**
     * 自动扫描（MediaStore）+ 用户手动添加的文件夹，两路合并去重。
     *
     * 去重口径用「标题 + 文件大小」：同一个文件可能既被媒体库索引、又在用户选的目录里，
     * 两条都进库会出现重复曲目。保留 MediaStore 那条——它的 URI 权限更稳定。
     */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val scanned = LocalMusicScanner.scan(context)
        if (scanned.isNotEmpty()) dao.upsertAll(scanned)
        val known = scanned.map { it.title to it.sizeBytes }.toHashSet()
        folderDao.all().forEach { folder ->
            val treeUri = runCatching { Uri.parse(folder.uri) }.getOrNull() ?: return@forEach
            val items = runCatching { FolderMusicScanner.scan(context, treeUri) }
                .getOrElse { emptyList() }
                .filterNot { (it.title to it.sizeBytes) in known }
            if (items.isNotEmpty()) dao.upsertAll(items)
            folderDao.upsert(folder.copy(lastScanAt = System.currentTimeMillis()))
        }
        // 早先 addFolder 曾绕过这里直接入库，可能已经留下重复行；每次刷新顺手清一次。
        dao.pruneDuplicates()
        dao.count()
    }

    suspend fun addedFolders(): List<LocalFolderEntity> = withContext(Dispatchers.IO) { folderDao.all() }

    /** 添加一个文件夹：拿到持久授权 → 落库 → 立刻扫一遍。返回本次新增可见曲目数（累计总数）。 */
    suspend fun addFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        folderDao.upsert(
            LocalFolderEntity(
                uri = treeUri.toString(),
                displayPath = FolderMusicScanner.displayNameOf(treeUri),
                addedAt = System.currentTimeMillis(),
            ),
        )
        // 走统一刷新：去重口径只写在一处，避免「添加目录」和「重新扫描」两套结果。
        refresh()
    }

    /** 移除文件夹：连它扫出来的曲目一起清掉（MediaStore 来源的不受影响）。 */
    suspend fun removeFolder(uri: String) = withContext(Dispatchers.IO) {
        folderDao.deleteTracksOf(uri)
        folderDao.delete(uri)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun tracks(): List<Track> = withContext(Dispatchers.IO) { dao.all().map(::toTrack) }

    suspend fun recentPage(page: Int, size: Int): Page<Track> = withContext(Dispatchers.IO) {
        val items = dao.recentPage(size, page * size).map(::toTrack)
        Page(items = items, page = page, pageSize = size, total = dao.count(), sort = "recent")
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else dao.search(query.trim()).map(::toTrack)
    }

    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        dao.all().filter { !it.albumName.isNullOrBlank() }
            .groupBy { it.albumName!! }
            .map { (name, items) ->
                Album(
                    guid = CollectionGuid("local:album:$name"),
                    name = name,
                    artistName = items.firstNotNullOfOrNull { it.artistName },
                    coverId = null,
                    trackCount = items.size,
                    releaseDate = items.firstNotNullOfOrNull { it.year?.toString() },
                )
            }
            .sortedBy { it.name }
    }

    suspend fun artists(): List<Artist> = withContext(Dispatchers.IO) {
        dao.all().filter { !it.artistName.isNullOrBlank() }
            .groupBy { it.artistName!! }
            .map { (name, items) ->
                Artist(
                    guid = CollectionGuid("local:artist:$name"),
                    name = name,
                    coverId = null,
                    trackCount = items.size,
                    albumCount = items.mapNotNull { it.albumName }.distinct().size,
                )
            }
            .sortedBy { it.name }
    }

    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        dao.all().mapNotNull { it.relativePath?.trimEnd('/')?.takeIf { p -> p.isNotBlank() } }
            .distinct()
            .sorted()
    }

    suspend fun tracksOfAlbum(name: String): List<Track> = withContext(Dispatchers.IO) {
        dao.all().filter { it.albumName == name }.sortedBy { it.trackNo }.map(::toTrack)
    }

    suspend fun tracksOfArtist(name: String): List<Track> = withContext(Dispatchers.IO) {
        dao.all().filter { it.artistName == name }.map(::toTrack)
    }

    suspend fun tracksOfFolder(path: String): List<Track> = withContext(Dispatchers.IO) {
        dao.all().filter { it.relativePath?.trimEnd('/') == path }.map(::toTrack)
    }

    suspend fun byUri(uri: String): Track? = withContext(Dispatchers.IO) {
        dao.byUri(uri)?.let(::toTrack)
    }

    /** 外部 intent 带进来的单曲：不自动入库，直接构造可播项。 */
    fun externalTrack(uri: String, displayName: String?): Track = Track(
        guid = TrackGuid("local:external:$uri"),
        title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "本地音频",
        artistName = null,
        albumName = null,
        coverId = null,
        durationMs = null,
        isCue = false,
        origin = TrackOrigin.Local,
        localUri = uri,
    )

    /** 本地曲目不需要联网取链，streamUrl 直接就是 content:// URI。 */
    fun prepare(track: Track): PlaybackTrack = PlaybackTrack(
        track = track,
        streamUrl = track.localUri.orEmpty(),
        artworkUrl = null,
    )

    suspend fun markMissing(uri: String) = withContext(Dispatchers.IO) { dao.markMissing(uri) }

    suspend fun purgeMissing(): Int = withContext(Dispatchers.IO) { dao.purgeMissing() }

    private fun toTrack(entity: LocalTrackEntity): Track = Track(
        // MediaStore 曲目沿用旧 guid（收藏表按 guid 记录，换格式会让已收藏条目失效）；
        // 文件夹来源没有 mediaStoreId，用 document URI 保证唯一。
        guid = TrackGuid(
            if (entity.mediaStoreId > 0) "local:track:${entity.mediaStoreId}"
            else "local:doc:${entity.uri}",
        ),
        title = entity.title,
        artistName = entity.artistName,
        albumName = entity.albumName,
        coverId = null,
        durationMs = entity.durationMs,
        isCue = false,
        audioFormat = entity.mimeType?.substringAfterLast('/')?.uppercase(),
        origin = TrackOrigin.Local,
        localUri = entity.uri,
    )
}
