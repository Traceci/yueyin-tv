package com.yueyin.tv.update

import java.net.URI

/** 更新地址的准入策略，抽成接口是为了单元测试能指向本地 MockWebServer。 */
internal interface UpdateUrlPolicy {
    fun requireManifest(uri: URI): URI
    fun requireApk(uri: URI): URI
    fun resolveRedirect(base: URI, location: String, label: String): URI
}

/**
 * 线上更新只接受悦音自己仓库发布的地址。
 *
 * 更新清单与安装包都必须在 [REPO] 之下（GitHub 直链、raw 或 jsDelivr 镜像），
 * 即使清单在传输过程中被改写，也无法把安装包指向任意站点；再叠加 APK 的
 * SHA-256 与签名证书校验，构成三层保护。
 */
internal object YueyinUpdatePolicy : UpdateUrlPolicy {
    private const val REPO = "Traceci/yueyin-tv"

    /** 清单地址允许的域名：GitHub 直链、raw、以及 jsDelivr 的几个 CDN 节点。 */
    private val manifestHosts = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "cdn.jsdelivr.net",
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net",
    )

    /** Release 资源会 302 到 GitHub 的对象存储，这两个域名只在跳转里出现。 */
    private val signedAssetHosts = setOf(
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    override fun requireManifest(uri: URI): URI {
        requireHost(uri, manifestHosts, "更新地址")
        if (!uri.path.orEmpty().contains(REPO, ignoreCase = true)) {
            throw UpdateFailure("更新地址不是悦音的发布渠道")
        }
        return uri
    }

    override fun requireApk(uri: URI): URI {
        requireHost(uri, manifestHosts + signedAssetHosts, "APK 地址")
        val host = uri.host.lowercase()
        if (host !in signedAssetHosts && !uri.path.orEmpty().contains(REPO, ignoreCase = true)) {
            throw UpdateFailure("APK 地址不是悦音的发布渠道")
        }
        return uri
    }

    override fun resolveRedirect(base: URI, location: String, label: String): URI {
        val resolved = runCatching { base.resolve(location) }.getOrNull()
            ?: throw UpdateFailure("$label 跳转目标无效")
        return validateHttpsUri(resolved.toString(), label)
    }

    private fun requireHost(uri: URI, hosts: Set<String>, label: String) {
        val host = uri.host?.lowercase()
        if (host == null || host !in hosts) throw UpdateFailure("$label 不受支持（${uri.host ?: "空域名"}）")
    }
}
