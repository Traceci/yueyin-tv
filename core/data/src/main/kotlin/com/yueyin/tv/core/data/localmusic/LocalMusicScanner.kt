package com.yueyin.tv.core.data.localmusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 音频扫描。投影只取各 Android 版本都稳定存在的基础列，
 * 可选列（音轨号 / 年份 / 相对路径）通过 getColumnIndex 动态探测，避免不同 ROM 上炸列。
 */
object LocalMusicScanner {

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private val projection: Array<String>
        get() = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            // TRACK / YEAR 在部分 ROM 上列名存在但值异常，统一走可选列探测。
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
        }.toTypedArray()

    /** 时长小于 30 秒的一般是提示音 / 录音碎片，不进曲库。 */
    private const val MIN_DURATION_MS = 30_000L

    suspend fun scan(context: Context): List<LocalTrackEntity> = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val cursor = runCatching {
            resolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.DURATION} >= ?",
                arrayOf(MIN_DURATION_MS.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return@withContext emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val trackIdx = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearIdx = c.getColumnIndex(MediaStore.Audio.Media.YEAR)

            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val rawTitle = c.getString(titleIdx).orEmpty()
                    val display = c.getString(nameIdx).orEmpty()
                    val title = rawTitle.ifBlank {
                        display.substringBeforeLast('.').ifBlank { "未知曲目" }
                    }
                    add(
                        LocalTrackEntity(
                            uri = uri,
                            mediaStoreId = id,
                            title = title,
                            artistName = c.getString(artistIdx)?.takeIf { it.isNotBlank() && it != "<unknown>" },
                            albumName = c.getString(albumIdx)?.takeIf { it.isNotBlank() && it != "<unknown>" },
                            durationMs = c.getLong(durationIdx),
                            trackNo = if (trackIdx >= 0) c.getInt(trackIdx) else 0,
                            year = if (yearIdx >= 0) c.getInt(yearIdx).takeIf { it in 1900..2100 } else null,
                            mimeType = c.getString(mimeIdx),
                            sizeBytes = c.getLong(sizeIdx),
                            dateAddedSec = c.getLong(dateIdx),
                            relativePath = if (relIdx >= 0) c.getString(relIdx) else null,
                            lastVerifiedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }
}
