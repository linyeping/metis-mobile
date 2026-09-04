package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mrgreenapps.a11ypilot.agent.CharacterCard

/**
 * 角色头像：优先渲染角色卡设置的头像（本地 URI 或网络 URL），
 * 没有设置时回退为「名字首字」占位块，保证列表里永远有可辨识的头像。
 */
@Composable
fun CharacterAvatar(
    card: CharacterCard?,
    size: Dp = 40.dp,
    corner: Dp = 10.dp
) {
    val name = card?.name.orEmpty().ifBlank { "角" }
    val avatarUri = card?.avatarUri.orEmpty()
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (avatarUri.isNotBlank()) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = name,
                    modifier = Modifier.size(size),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}
