package com.nefviewer.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    /** "copy" = 已拷贝进应用私有目录；"link" = 原地链接（SAF） */
    val mode: String,
    /** link 模式的 SAF tree URI（已持久化权限） */
    val sourceUri: String?,
    /** 展示用来源描述（如储存卡卷名 / 文件夹路径） */
    val sourceDisplayPath: String?,
    /** copy 模式的图库根目录（应用私有目录内）；SAF 自定义图库时为 null */
    val libraryPath: String?,
    /** copy 模式且用户自选图库目录时：项目在 SAF 树里的目录 URI */
    val libraryUri: String? = null,
) {
    companion object {
        const val MODE_COPY = "copy"
        const val MODE_LINK = "link"
    }
}

@Entity(
    tableName = "photos",
    indices = [Index("projectId")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val fileName: String,
    /** copy 模式：相对 libraryPath 的路径 */
    val relativePath: String?,
    /** link 模式：SAF document URI */
    val documentUri: String?,
    val rating: Int = 0,
    /** 方向归一化后的像素尺寸 */
    val width: Int,
    val height: Int,
    val dateTaken: Long?,
    val fileSize: Long,
)
