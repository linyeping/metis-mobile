package com.mrgreenapps.a11ypilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mrgreenapps.a11ypilot.ui.theme.FiraCodeFamily

/**
 * Markdown message renderer with code highlighting
 */
@Composable
fun MarkdownMessage(
    content: String,
    modifier: Modifier = Modifier
) {
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeText = MaterialTheme.colorScheme.onSecondaryContainer,
        codeBackground = MaterialTheme.colorScheme.secondaryContainer,
        dividerColor = MaterialTheme.colorScheme.outline
    )

    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineLarge,
        h2 = MaterialTheme.typography.headlineMedium,
        h3 = MaterialTheme.typography.headlineSmall,
        h4 = MaterialTheme.typography.titleLarge,
        h5 = MaterialTheme.typography.titleMedium,
        h6 = MaterialTheme.typography.titleSmall,
        text = MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodySmall.copy(fontFamily = FiraCodeFamily),
        quote = MaterialTheme.typography.bodyMedium,
        paragraph = MaterialTheme.typography.bodyMedium,
        ordered = MaterialTheme.typography.bodyMedium,
        bullet = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium
    )

    Markdown(
        content = content,
        colors = colors,
        typography = typography,
        modifier = modifier
    )
}

/**
 * Inline code block renderer
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        language?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FiraCodeFamily),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}
