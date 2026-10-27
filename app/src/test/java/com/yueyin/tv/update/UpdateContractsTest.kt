package com.yueyin.tv.update

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateContractsTest {
    @Test fun `valid manifest keeps the fixed package and the release URL`() {
        val result = validateManifest(validDto())

        assertEquals(22L, result.versionCode)
        assertEquals(APK_URL, result.apkUrl)
    }

    @Test fun `rejects APK hosted outside the project channel`() {
        val dto = validDto().copy(apk = validDto().apk.copy(url = "https://evil.example.com/app.apk"))

        assertThrows(UpdateFailure::class.java) { validateManifest(dto) }
    }

    @Test fun `rejects manifest from another jsDelivr repository`() {
        assertThrows(UpdateFailure::class.java) {
            YueyinUpdatePolicy.requireManifest(
                URI("https://cdn.jsdelivr.net/gh/someone-else/other-repo@main/update.json"),
            )
        }
    }

    @Test fun `accepts release asset and the signed object storage host`() {
        YueyinUpdatePolicy.requireApk(URI(APK_URL))
        YueyinUpdatePolicy.requireApk(
            URI("https://release-assets.githubusercontent.com/github-production-release-asset/1/2"),
        )
    }

    @Test fun `rejects unknown schema package uppercase digest and excessive notes`() {
        listOf(
            validDto().copy(schemaVersion = 2),
            validDto().copy(packageName = "com.example.other"),
            validDto().copy(apk = validDto().apk.copy(sha256 = "A".repeat(64))),
            validDto().copy(notes = "x".repeat(MAX_UPDATE_NOTES_LENGTH + 1)),
        ).forEach { dto ->
            assertThrows(UpdateFailure::class.java) { validateManifest(dto) }
        }
    }

    @Test fun `rejects insecure endpoint`() {
        assertThrows(UpdateFailure::class.java) { validateHttpsUri("http://download.example.com/update.json", "更新地址") }
    }

    private fun validDto() = UpdateManifestDto(
        schemaVersion = 1,
        packageName = UPDATE_PACKAGE_NAME,
        versionName = "1.0.6",
        versionCode = 22,
        title = "v1.0.6",
        notes = "更新内容",
        apk = UpdateApkDto(
            url = APK_URL,
            size = 1_024,
            sha256 = "a".repeat(64),
        ),
        publishedAt = "2026-08-11T00:00:00Z",
        githubReleaseUrl = "https://github.com/Traceci/yueyin-tv/releases/tag/v1.0.6",
    )

    private companion object {
        const val APK_URL = "https://github.com/Traceci/yueyin-tv/releases/download/v1.0.6/yueyin-1.0.6.apk"
    }
}
