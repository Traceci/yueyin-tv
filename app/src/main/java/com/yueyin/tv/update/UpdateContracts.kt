package com.yueyin.tv.update

import android.content.Intent
import com.yueyin.tv.BuildConfig
import kotlinx.serialization.Serializable

/**
 * 更新包必须与当前安装包同包名。这里取实际的 applicationId：
 * 共存包是 `com.yueyin.tv.fork`，写死上游的 `com.yueyin.tv` 会让自更新永远校验失败。
 */
internal val UPDATE_PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
internal const val MAX_UPDATE_MANIFEST_BYTES = 256 * 1024
internal const val MAX_UPDATE_NOTES_LENGTH = 4_000
internal const val MAX_UPDATE_REDIRECTS = 5

@Serializable
internal data class UpdateManifestDto(
    val schemaVersion: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val apk: UpdateApkDto,
    val publishedAt: String,
    val githubReleaseUrl: String,
)

@Serializable
internal data class UpdateApkDto(
    val url: String,
    val size: Long,
    val sha256: String,
)

internal data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
)

internal enum class UpdateCheckSource { Automatic, Manual }

internal sealed interface UpdateUiState {
    data object Disabled : UpdateUiState
    data object Idle : UpdateUiState
    data class Checking(val source: UpdateCheckSource) : UpdateUiState
    data class UpToDate(
        val currentVersionName: String,
        val source: UpdateCheckSource = UpdateCheckSource.Automatic,
    ) : UpdateUiState
    data class Available(
        val manifest: UpdateManifest,
        val ignored: Boolean,
        val source: UpdateCheckSource,
    ) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val downloadedBytes: Long) : UpdateUiState
    data class Verifying(val manifest: UpdateManifest) : UpdateUiState
    data class AwaitingInstallPermission(val manifest: UpdateManifest) : UpdateUiState
    data class PreparingInstaller(val manifest: UpdateManifest) : UpdateUiState
    data class AwaitingSystemConfirmation(val manifest: UpdateManifest) : UpdateUiState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateUiState
}

internal sealed interface UpdateEffect {
    data class LaunchIntent(val intent: Intent) : UpdateEffect
}

internal interface UpdateController {
    val state: kotlinx.coroutines.flow.StateFlow<UpdateUiState>
    val effects: kotlinx.coroutines.flow.Flow<UpdateEffect>
    val enabled: Boolean

    /** 最近一次检查完成的时间（`SystemClock.elapsedRealtime()`），从未检查过为 null。 */
    val lastCheckedAt: kotlinx.coroutines.flow.StateFlow<Long?>

    fun checkManually()
    fun ignoreAvailableVersion()
    fun dismiss()
    fun startDownload()
    fun cancelDownload()
    fun openInstallPermissionSettings()
    fun setAutomaticPromptAllowed(allowed: Boolean)
}
