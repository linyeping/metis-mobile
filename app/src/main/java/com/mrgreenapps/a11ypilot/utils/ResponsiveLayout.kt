package com.mrgreenapps.a11ypilot.utils

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive layout utilities for different screen sizes
 */
object ResponsiveLayout {

    /**
     * Screen size categories
     */
    enum class ScreenSize {
        SMALL,      // < 360dp (old/compact phones)
        COMPACT,    // 360-599dp (standard phones)
        MEDIUM,     // 600-839dp (large phones, small tablets)
        EXPANDED    // >= 840dp (tablets)
    }

    /**
     * Get current screen size category
     */
    @Composable
    fun getScreenSize(): ScreenSize {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp

        return when {
            screenWidth < 360 -> ScreenSize.SMALL
            screenWidth < 600 -> ScreenSize.COMPACT
            screenWidth < 840 -> ScreenSize.MEDIUM
            else -> ScreenSize.EXPANDED
        }
    }

    /**
     * Responsive padding based on screen size
     */
    @Composable
    fun responsivePadding(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 8.dp
            ScreenSize.COMPACT -> 12.dp
            ScreenSize.MEDIUM -> 16.dp
            ScreenSize.EXPANDED -> 24.dp
        }
    }

    /**
     * Responsive horizontal padding
     */
    @Composable
    fun responsiveHorizontalPadding(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 12.dp
            ScreenSize.COMPACT -> 16.dp
            ScreenSize.MEDIUM -> 24.dp
            ScreenSize.EXPANDED -> 32.dp
        }
    }

    /**
     * Responsive vertical padding
     */
    @Composable
    fun responsiveVerticalPadding(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 8.dp
            ScreenSize.COMPACT -> 12.dp
            ScreenSize.MEDIUM -> 16.dp
            ScreenSize.EXPANDED -> 20.dp
        }
    }

    /**
     * Responsive item spacing
     */
    @Composable
    fun responsiveSpacing(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 8.dp
            ScreenSize.COMPACT -> 12.dp
            ScreenSize.MEDIUM -> 16.dp
            ScreenSize.EXPANDED -> 20.dp
        }
    }

    /**
     * Responsive card width (for horizontal scrolling)
     */
    @Composable
    fun responsiveCardWidth(): Dp {
        val configuration = LocalConfiguration.current
        val screenWidth = configuration.screenWidthDp.dp

        return when (getScreenSize()) {
            ScreenSize.SMALL -> screenWidth * 0.85f
            ScreenSize.COMPACT -> screenWidth * 0.80f
            ScreenSize.MEDIUM -> screenWidth * 0.70f
            ScreenSize.EXPANDED -> 600.dp
        }
    }

    /**
     * Responsive column count for grid layouts
     */
    @Composable
    fun responsiveColumnCount(): Int {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 1
            ScreenSize.COMPACT -> 1
            ScreenSize.MEDIUM -> 2
            ScreenSize.EXPANDED -> 3
        }
    }

    /**
     * Check if layout should use compact mode
     */
    @Composable
    fun isCompactLayout(): Boolean {
        return getScreenSize() in listOf(ScreenSize.SMALL, ScreenSize.COMPACT)
    }

    /**
     * Check if layout should use expanded mode
     */
    @Composable
    fun isExpandedLayout(): Boolean {
        return getScreenSize() in listOf(ScreenSize.MEDIUM, ScreenSize.EXPANDED)
    }

    /**
     * Get responsive icon size
     */
    @Composable
    fun responsiveIconSize(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 20.dp
            ScreenSize.COMPACT -> 24.dp
            ScreenSize.MEDIUM -> 28.dp
            ScreenSize.EXPANDED -> 32.dp
        }
    }

    /**
     * Get responsive button height
     */
    @Composable
    fun responsiveButtonHeight(): Dp {
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 44.dp
            ScreenSize.COMPACT -> 48.dp
            ScreenSize.MEDIUM -> 52.dp
            ScreenSize.EXPANDED -> 56.dp
        }
    }

    /**
     * Responsive minimum touch target size (for accessibility)
     */
    @Composable
    fun responsiveTouchTargetSize(): Dp {
        // Minimum 48dp for accessibility compliance
        return when (getScreenSize()) {
            ScreenSize.SMALL -> 44.dp
            else -> 48.dp
        }
    }
}
