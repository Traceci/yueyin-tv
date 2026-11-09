package com.yueyin.tv.core.data.localmusic

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 用户手动添加的本地文件夹（SAF 目录授权）。
 *
 * 存在的意义：MediaStore 只反映系统媒体索引，文件刚拷入、放在 OTG/SD 卡、
 * 目录带 .nomedia 时都查不到。这条路径绕过系统索引，直接按目录树读文件。
 */
@Entity(tableName = "local_folder")
data class LocalFolderEntity(
    @PrimaryKey val uri: String,
    val displayPath: String,
    val addedAt: Long,
    val lastScanAt: Long = 0L,
)

@Dao
interface LocalFolderDao {
    @Query("SELECT * FROM local_folder ORDER BY addedAt")
    suspend fun all(): List<LocalFolderEntity>

    @Query("SELECT COUNT(*) FROM local_folder")
    suspend fun count(): Int

    @Query("SELECT * FROM local_folder WHERE uri = :uri")
    suspend fun byUri(uri: String): LocalFolderEntity?

    @androidx.room.Upsert
    suspend fun upsert(folder: LocalFolderEntity)

    @Query("DELETE FROM local_folder WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM local_track WHERE folderUri = :uri")
    suspend fun deleteTracksOf(uri: String)
}
