package com.nefviewer.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 1-5 星打分条；rating=0 表示未评分 */
@Composable
fun RatingBar(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier = modifier) {
        for (i in 1..5) {
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = "$i 星",
                tint = if (i <= rating) starColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onRate(if (rating == i) 0 else i) },
            )
        }
    }
}
