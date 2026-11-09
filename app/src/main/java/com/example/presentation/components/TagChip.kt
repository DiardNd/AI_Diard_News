package com.example.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TagItem

@Composable
fun TagChip(
    tag: TagItem,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null
) {
    val tagColor = parseColor(tag.colorHex)

    val backgroundColor = when {
        isSelected -> tagColor.copy(alpha = 0.35f)
        else -> tagColor.copy(alpha = 0.12f)
    }

    val borderColor = when {
        isSelected -> tagColor
        else -> tagColor.copy(alpha = 0.3f)
    }

    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .testTag("tag_chip_${tag.id}")
            .clip(shape)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tagColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface
        )

        if (onRemoveClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Удалить тег",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable { onRemoveClick() }
            )
        }
    }
}

fun parseColor(hex: String): Color {
    return try {
        val clean = hex.removePrefix("#")
        val full = if (clean.length == 6) "FF$clean" else clean
        Color(full.toLong(16))
    } catch (_: Exception) {
        Color(0xFF00D2D3)
    }
}
