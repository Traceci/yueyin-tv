package com.yueyin.tv.core.data.localmusic

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * 本机音频索引。只存 MediaStore 的 content URI 与元数据，**不复制、不接管音频文件**。
 * 用户删除文件后本表条目不会立刻消失，由播放失败标记为 missing，再由设置页统一清理。
 */
@Entity(tableName = "local_track")
data class LocalTrackEntity(
    @PrimaryKey val uri: String,
    val mediaStoreId: Long,
    val title: String,
    val artistName: String?,
    val albumName: String?,
    val durationMs: Long,
    val trackNo: Int,
    val year: Int?,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val relativePath: String?,
    /** 来自用户手动添加的文件夹（SAF tree URI）；为空表示来自 MediaStore 自动扫描。 */
    val folderUri: String = "",
    val missing: Boolean = false,
    val lastVerifiedAt: Long = 0L,
)

@Dao
interface LocalMusicDao {
    @Query("SELECT * FROM local_track WHERE missing = 0 ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_track WHERE missing = 0 ORDER BY dateAddedSec DESC LIMIT :limit OFFSET :offset")
    suspend fun recentPage(limit: Int, offset: Int): List<LocalTrackEntity>

    @Query("SELECT COUNT(*) FROM local_track WHERE missing = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM local_track WHERE uri = :uri")
    suspend fun byUri(uri: String): LocalTrackEntity?

    @Query(
        "SELECT * FROM local_track WHERE missing = 0 AND " +
            "(title LIKE '%' || :q || '%' OR artistName LIKE '%' || :q || '%' OR albumName LIKE '%' || :q || '%') " +
            "ORDER BY title COLLATE NOCASE"
    )
    suspend fun search(q: String): List<LocalTrackEntity>

    @Upsert
    suspend fun upsertAll(items: List<LocalTrackEntity>)

    @Query("UPDATE local_track SET missing = 1 WHERE uri = :uri")
    suspend fun markMissing(uri: String)

    @Query("DELETE FROM local_track WHERE missing = 1")
    suspend fun purgeMissing(): Int

    @Query("DELETE FROM local_track")
    suspend fun clear(): Int

    /**
     * 清掉「文件夹来源」与「媒体库来源」重复的行（同标题同大小）。
     *
     * 两路扫描的 URI 不同（media/39 vs documents/...），主键去重拦不住；
     * 保留 MediaStore 那条——它的 URI 权限更稳，且收藏可能已经指向它。
     */
    @Query(
        "DELETE FROM local_track WHERE mediaStoreId = 0 AND (title || '|' || sizeBytes) IN " +
            "(SELECT title || '|' || sizeBytes FROM local_track WHERE mediaStoreId > 0)"
    )
    suspend fun pruneDuplicates(): Int

    @Query("SELECT MAX(dateAddedSec) FROM local_track")
    suspend fun lastAddedSec(): Long?
}
