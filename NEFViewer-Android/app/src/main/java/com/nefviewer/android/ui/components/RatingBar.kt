package com.nefviewer.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 1-5 星打分条；rating=0 表示未评分。showClear 时前面带显式清零按钮 */
@Composable
fun RatingBar(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starColor: Color = MaterialTheme.colorScheme.primary,
    showClear: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showClear) {
            Text(
                "清除评分",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onRate(0) }
                    .padding(horizontal = 8.dp),
            )
        }
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
