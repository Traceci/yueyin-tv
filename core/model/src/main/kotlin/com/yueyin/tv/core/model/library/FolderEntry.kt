// SPDX-License-Identifier: GPL-3.0-only
package com.yueyin.tv.core.model.library

/**
 * 平台目录浏览的统一条目。
 *
 * id 是平台内部标识；parentId 为 null 表示根目录。
 * 文件夹与音频可混排，UI 按 isFolder 分别显示数量或歌手/时长。
 */
data class FolderEntry(
    val id: String,
    val parentId: String?,
    val name: String,
    val subtitle: String = "",
    val isFolder: Boolean,
    val childCount: Int = 0,
    val path: String? = null,
    /** 以下字段只对音频有效：folder-view/tracks 里本来就带这些字段，之前被丢掉了。 */
    val coverId: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val durationMs: Long? = null,
)
