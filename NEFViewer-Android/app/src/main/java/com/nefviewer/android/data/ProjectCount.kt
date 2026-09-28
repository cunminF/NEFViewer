package com.nefviewer.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** GROUP BY 查询用的投影 */
data class ProjectCount(
    val projectId: String,
    val cnt: Int,
)
