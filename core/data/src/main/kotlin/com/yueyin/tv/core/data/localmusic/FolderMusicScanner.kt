package com.yueyin.tv.core.data.localmusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按用户授权的文件夹（SAF tree）递归扫描，绕过 MediaStore 索引。
 *
 * 与自动扫描同一套表、同一个 `content://` 播放链路，区别只在「文件是怎么被找到的」。
 * 用户明确要求 30 秒以下的音频不收（试听/提示音会污染曲库），这里与 MediaStore 口径保持一致。
 */
internal object FolderMusicScanner {
    private const val MIN_DURATION_MS = 30_000L
    private const val MAX_DEPTH = 8
    private const val MAX_FILES = 20_000

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma",
        "ape", "dsf", "dff", "mka", "alac", "aiff", "aif", "mp4a",
    )

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (scanned: Int) -> Unit = {},
    ): List<LocalTrackEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val result = ArrayList<LocalTrackEntity>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyList()
        val rootName = runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull().orEmpty()
        val pending = ArrayDeque<Pair<String, String>>() // documentId to 展示路径
        pending.add(rootId to "")
        var visited = 0

        while (pending.isNotEmpty() && result.size < MAX_FILES) {
            val (documentId, path) = pending.removeFirst()
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            }.getOrNull() ?: continue
            val cursor = runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )
            }.getOrNull() ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val childPath = if (path.isBlank()) name else "$path/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (childPath.count { it == '/' } < MAX_DEPTH) pending.add(childId to childPath)
                        continue
                    }
                    if (!isAudio(name, mime)) continue
                    visited += 1
                    if (visited % 20 == 0) onProgress(result.size)
                    val entity = runCatching {
                        readTrack(context, treeUri, childId, name, mime, size, modified, path, rootName, rootId)
                    }.getOrNull() ?: continue
                    if (entity.durationMs < MIN_DURATION_MS) continue
                    result.add(entity)
                }
            }
        }
        onProgress(result.size)
        result
    }

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    private fun readTrack(
        context: Context,
        treeUri: Uri,
        documentId: String,
        name: String,
        mime: String,
        size: Long,
        modified: Long,
        parentPath: String,
        rootName: String,
        rootId: String,
    ): LocalTrackEntity {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        var title = name.substringBeforeLast('.').ifBlank { name }
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var trackNo = 0
        var year: Int? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()
        } catch (_: Exception) {
            // 读不到标签就退回文件名，不让一条坏文件中断整目录扫描。
        } finally {
            runCatching { retriever.release() }
        }
        val relativePath = when {
            parentPath.isBlank() -> rootName
            else -> parentPath
        }
        return LocalTrackEntity(
            uri = uri.toString(),
            mediaStoreId = 0L,
            title = title,
            artistName = artist,
            albumName = album,
            durationMs = duration,
            trackNo = trackNo,
            year = year,
            mimeType = mime.takeIf { it.isNotBlank() } ?: "audio/mpeg",
            sizeBytes = size,
            dateAddedSec = if (modified > 0) modified / 1000 else System.currentTimeMillis() / 1000,
            relativePath = relativePath,
            folderUri = treeUri.toString(),
        )
    }

    /** tree URI 的末段就是用户选中的目录名，拿不到时退回整串，只用于展示。 */
    fun displayNameOf(treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return treeUri.toString()
        val raw = documentId.substringAfterLast(':').substringAfterLast('/')
        return raw.ifBlank { documentId }
    }
}
