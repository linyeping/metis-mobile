package com.mrgreenapps.a11ypilot.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation destinations for the app.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Cowork : Screen("cowork", "Cowork", Icons.Default.WorkOutline)
    object Code : Screen("code", "Code", Icons.Default.Code)
    object Settings : Screen("settings", "设置", Icons.Default.Settings)

    companion object {
        val items = listOf(Chat, Cowork, Code, Settings)
    }
}
