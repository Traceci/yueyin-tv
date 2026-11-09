package com.yueyin.tv.core.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yueyin.tv.core.data.localmusic.LocalFolderDao
import com.yueyin.tv.core.data.localmusic.LocalFolderEntity
import com.yueyin.tv.core.data.localmusic.LocalMusicDao
import com.yueyin.tv.core.data.localmusic.LocalTrackEntity

@Entity(tableName = "account_state", primaryKeys = ["namespace"])
data class AccountStateEntity(
    val namespace: String,
    val queueJson: String? = null,
    val frozenQueueJson: String? = null,
    val playerStyle: String? = null,
    val cacheBudget: String? = null,
    val onlineLyricsMatchingEnabled: Boolean = true,
    val schemaRevision: Int = 1,
    val updatedAt: Long,
)

@Entity(tableName = "cache_page", primaryKeys = ["namespace", "sourceKey", "page"])
data class CachedPageEntity(
    val namespace: String,
    val sourceKey: String,
    val page: Int,
    val payload: String,
    val total: Int,
    val sort: String,
    val accessedAt: Long,
)

@Entity(tableName = "cache_lyric", primaryKeys = ["namespace", "trackGuid"])
data class CachedLyricEntity(
    val namespace: String,
    val trackGuid: String,
    val payload: String,
    val accessedAt: Long,
)

@Entity(tableName = "cache_matched_lyric", primaryKeys = ["namespace", "trackGuid"])
data class CachedMatchedLyricEntity(
    val namespace: String,
    val trackGuid: String,
    val payload: String,
    val accessedAt: Long,
)

@Entity(tableName = "cache_index", primaryKeys = ["namespace", "indexKey"])
data class CachedIndexEntity(
    val namespace: String,
    val indexKey: String,
    val payload: String,
    val accessedAt: Long,
)

/**
 * 本地收藏：文件类协议（WebDAV / SMB / 群晖 AudioStation）和在线音源都没有服务端收藏接口，
 * 收藏落在本机 Room 里。这是**用户数据**，不参与缓存预算的淘汰。
 *
 * 在线音源的收藏除了展示信息，还要留下取链所需的元数据：在线歌曲的播放链接是音源脚本
 * 用 platform / id / mediaId / albumId / sourceName 现场算出来的，而 songIndex 是纯内存结构，
 * 进程一退出就没了。只存标题歌手的话，冷启动后收藏列表还在，但点播必然取不到链。
 */
@Entity(tableName = "favorite_track", primaryKeys = ["namespace", "trackGuid"])
data class FavoriteTrackEntity(
    val namespace: String,
    val trackGuid: String,
    val title: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val coverId: String? = null,
    val durationMs: Long = 0L,
    val addedAt: Long,
    /** 在线歌曲：平台内专辑 id（QQ 用 albumMid）。非在线源留空。 */
    val albumId: String = "",
    /** 在线歌曲：酷狗 hash / QQ songmid，与 id 不同时取链必须用它。非在线源留空。 */
    val mediaId: String = "",
    /** 在线歌曲：产生这条结果的音源脚本文件名，用于取链时优先选同一份脚本。 */
    val sourceName: String = "",
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureAccount(entity: AccountStateEntity)

    @Query("SELECT * FROM account_state WHERE namespace = :namespace")
    suspend fun account(namespace: String): AccountStateEntity?

    @Query("UPDATE account_state SET queueJson = :queueJson, updatedAt = :updatedAt WHERE namespace = :namespace")
    suspend fun updateQueue(namespace: String, queueJson: String?, updatedAt: Long)

    @Query("UPDATE account_state SET frozenQueueJson = :queueJson, updatedAt = :updatedAt WHERE namespace = :namespace")
    suspend fun updateFrozenQueue(namespace: String, queueJson: String?, updatedAt: Long)

    @Query(
        "UPDATE account_state SET queueJson = :snapshotJson, frozenQueueJson = NULL, " +
            "updatedAt = :updatedAt WHERE namespace = :namespace",
    )
    suspend fun updatePlaybackSnapshot(namespace: String, snapshotJson: String?, updatedAt: Long)

    @Query(
        "UPDATE account_state SET playerStyle = :style, cacheBudget = :budget, " +
            "onlineLyricsMatchingEnabled = :onlineLyricsMatchingEnabled, updatedAt = :updatedAt " +
            "WHERE namespace = :namespace",
    )
    suspend fun updateSettings(
        namespace: String,
        style: String,
        budget: String,
        onlineLyricsMatchingEnabled: Boolean,
        updatedAt: Long,
    )

    @Upsert
    suspend fun upsertPage(entity: CachedPageEntity)

    @Query("SELECT * FROM cache_page WHERE namespace = :namespace AND sourceKey = :sourceKey AND page = :page")
    suspend fun page(namespace: String, sourceKey: String, page: Int): CachedPageEntity?

    @Query("UPDATE cache_page SET accessedAt = :accessedAt WHERE namespace = :namespace AND sourceKey = :sourceKey AND page = :page")
    suspend fun touchPage(namespace: String, sourceKey: String, page: Int, accessedAt: Long)

    @Upsert
    suspend fun upsertLyric(entity: CachedLyricEntity)

    @Query("SELECT * FROM cache_lyric WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun lyric(namespace: String, trackGuid: String): CachedLyricEntity?

    @Query("UPDATE cache_lyric SET accessedAt = :accessedAt WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun touchLyric(namespace: String, trackGuid: String, accessedAt: Long)

    @Upsert
    suspend fun upsertMatchedLyric(entity: CachedMatchedLyricEntity)

    @Query("SELECT * FROM cache_matched_lyric WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun matchedLyric(namespace: String, trackGuid: String): CachedMatchedLyricEntity?

    @Query("UPDATE cache_matched_lyric SET accessedAt = :accessedAt WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun touchMatchedLyric(namespace: String, trackGuid: String, accessedAt: Long)

    @Upsert
    suspend fun upsertIndex(entity: CachedIndexEntity)

    @Query("SELECT * FROM cache_index WHERE namespace = :namespace AND indexKey = :indexKey")
    suspend fun index(namespace: String, indexKey: String): CachedIndexEntity?

    @Query("UPDATE cache_index SET accessedAt = :accessedAt WHERE namespace = :namespace AND indexKey = :indexKey")
    suspend fun touchIndex(namespace: String, indexKey: String, accessedAt: Long)

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_page")
    suspend fun pagePayloadBytes(): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_lyric")
    suspend fun lyricPayloadBytes(): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_matched_lyric")
    suspend fun matchedLyricPayloadBytes(): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_index")
    suspend fun indexPayloadBytes(): Long

    @Query("DELETE FROM cache_page WHERE rowid IN (SELECT rowid FROM cache_page ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestPages(count: Int): Int

    @Query("DELETE FROM cache_lyric WHERE rowid IN (SELECT rowid FROM cache_lyric ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestLyrics(count: Int): Int

    @Query("DELETE FROM cache_matched_lyric WHERE rowid IN (SELECT rowid FROM cache_matched_lyric ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestMatchedLyrics(count: Int): Int

    @Query("DELETE FROM cache_index WHERE rowid IN (SELECT rowid FROM cache_index ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestIndexes(count: Int): Int

    @Query("DELETE FROM cache_page WHERE namespace = :namespace")
    suspend fun deletePages(namespace: String)

    @Query("DELETE FROM cache_lyric WHERE namespace = :namespace")
    suspend fun deleteLyrics(namespace: String)

    @Query("DELETE FROM cache_matched_lyric WHERE namespace = :namespace")
    suspend fun deleteMatchedLyrics(namespace: String)

    @Query("DELETE FROM cache_index WHERE namespace = :namespace")
    suspend fun deleteIndexes(namespace: String)

    @Query("DELETE FROM cache_page")
    suspend fun deleteAllPages()

    @Query("DELETE FROM cache_lyric")
    suspend fun deleteAllLyrics()

    @Query("DELETE FROM cache_matched_lyric")
    suspend fun deleteAllMatchedLyrics()

    @Query("DELETE FROM cache_index")
    suspend fun deleteAllIndexes()

    @Query("DELETE FROM account_state WHERE namespace = :namespace")
    suspend fun deleteAccount(namespace: String)

    // ---------------------------------------------------------------- 本地收藏

    @Upsert
    suspend fun upsertFavorite(entity: FavoriteTrackEntity)

    @Query("DELETE FROM favorite_track WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun deleteFavorite(namespace: String, trackGuid: String)

    @Query("SELECT trackGuid FROM favorite_track WHERE namespace = :namespace")
    suspend fun favoriteGuids(namespace: String): List<String>

    @Query("SELECT * FROM favorite_track WHERE namespace = :namespace ORDER BY addedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun favoritePage(namespace: String, limit: Int, offset: Int): List<FavoriteTrackEntity>

    @Query("SELECT COUNT(*) FROM favorite_track WHERE namespace = :namespace")
    suspend fun favoriteCount(namespace: String): Int
}

@Database(
    entities = [
        AccountStateEntity::class,
        CachedPageEntity::class,
        CachedLyricEntity::class,
        CachedMatchedLyricEntity::class,
        CachedIndexEntity::class,
        FavoriteTrackEntity::class,
        LocalTrackEntity::class,
        LocalFolderEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    /** 本地曲库独立 DAO：与在线/协议平台的缓存表完全分开。 */
    abstract fun localMusicDao(): LocalMusicDao

    /** 手动添加的本地文件夹（SAF 目录授权）。 */
    abstract fun localFolderDao(): LocalFolderDao

    companion object {
        const val NAME = "fn_music_tv.db"
        const val MAX_DATABASE_BYTES = 32L * 1024L * 1024L
        const val EVICTABLE_PAYLOAD_TARGET_BYTES = 24L * 1024L * 1024L

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account_state ADD COLUMN schemaRevision INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE account_state ADD COLUMN " +
                        "onlineLyricsMatchingEnabled INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cache_matched_lyric (" +
                        "namespace TEXT NOT NULL, " +
                        "trackGuid TEXT NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "accessedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(namespace, trackGuid))",
                )
            }
        }

        /** 本地收藏表：文件类协议（WebDAV/SMB/AudioStation）的收藏落在本机。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorite_track (" +
                        "namespace TEXT NOT NULL, " +
                        "trackGuid TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "artistName TEXT NOT NULL, " +
                        "albumName TEXT NOT NULL, " +
                        "coverId TEXT, " +
                        "durationMs INTEGER NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(namespace, trackGuid))",
                )
            }
        }

        /** 本地收藏补齐在线取链元数据：老行留空，冷启动时按 guid 退化为最小取链对象。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_track ADD COLUMN albumId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorite_track ADD COLUMN mediaId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorite_track ADD COLUMN sourceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 本地曲库索引表。只新增表，不改动 favorite_track 等既有表，
         * 因此 2.1.0 之前收藏的在线歌曲元数据不会丢。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_track (" +
                        "uri TEXT NOT NULL, " +
                        "mediaStoreId INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "artistName TEXT, " +
                        "albumName TEXT, " +
                        "durationMs INTEGER NOT NULL, " +
                        "trackNo INTEGER NOT NULL, " +
                        "year INTEGER, " +
                        "mimeType TEXT, " +
                        "sizeBytes INTEGER NOT NULL, " +
                        "dateAddedSec INTEGER NOT NULL, " +
                        "relativePath TEXT, " +
                        "missing INTEGER NOT NULL, " +
                        "lastVerifiedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(uri))",
                )
            }
        }

        /**
         * 6 → 7：新增「手动添加的文件夹」表，并给 local_track 补 folderUri 列。
         * 只加不改：既有 MediaStore 曲目 folderUri 默认空串，收藏表一行不动。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_folder (" +
                        "uri TEXT NOT NULL, " +
                        "displayPath TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "lastScanAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(uri))",
                )
                db.execSQL("ALTER TABLE local_track ADD COLUMN folderUri TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val autoVacuum = db.query("PRAGMA auto_vacuum").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (autoVacuum != AUTO_VACUUM_INCREMENTAL) {
                        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                        db.execSQL("VACUUM")
                    }
                    val pageSize = db.query("PRAGMA page_size").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 4_096L
                    }
                    db.query("PRAGMA max_page_count=${MAX_DATABASE_BYTES / pageSize}").close()
                    db.query("PRAGMA journal_size_limit=2097152").close()
                    db.query("PRAGMA wal_autocheckpoint=256").close()
                }
            })
            .build()

        private const val AUTO_VACUUM_INCREMENTAL = 2
    }
}
