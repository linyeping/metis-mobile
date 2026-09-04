package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mrgreenapps.a11ypilot.data.WorkMode

sealed class NavItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val mode: WorkMode?
) {
    data object Chat : NavItem("chat", "聊天", Icons.Default.ChatBubbleOutline, WorkMode.CHAT)
    data object Cowork : NavItem("cowork", "协作", Icons.Default.WorkOutline, WorkMode.COWORK)
    data object Code : NavItem("code", "编程", Icons.Default.Code, WorkMode.CODE)
    data object Settings : NavItem("settings", "设置", Icons.Default.Settings, null)
}

@Composable
fun BottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        modifier = Modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        listOf(
            NavItem.Chat,
            NavItem.Cowork,
            NavItem.Code,
            NavItem.Settings
        ).forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.title) },
                label = { Text(item.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
