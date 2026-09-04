package com.mrgreenapps.a11ypilot.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.data.Message
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.Session
import com.mrgreenapps.a11ypilot.data.SessionRepository
import com.mrgreenapps.a11ypilot.data.UsageEntry
import com.mrgreenapps.a11ypilot.data.UsageRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private enum class UsageTab(val title: String) {
    OVERVIEW("概览"),
    MODELS("模型"),
    DETAILS("明细")
}

private enum class UsageRange(val title: String, val days: Int) {
    SEVEN("7天", 7),
    THIRTY("30天", 30),
    NINETY("90天", 90)
}

private data class UsageSummary(
    val sessions: Int,
    val messages: Int,
    val totalTokens: Long,
    val activeDays: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val peakHour: Int?,
    val favoriteModel: String,
    val dailyTokens: List<Long>,
    val activeDates: Set<LocalDate>,
    val dailyTokensByDate: Map<LocalDate, Long>
)

private data class ModelSummary(
    val model: String,
    val input: Long,
    val output: Long,
    val total: Long,
    val percentage: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(onRefresh: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionRepository = remember { SessionRepository(context) }
    val sessions by sessionRepository.observeSessions().collectAsState(initial = emptyList())
    val messages by sessionRepository.observeAllMessages().collectAsState(initial = emptyList())
    val entries by UsageRepository.observe(context).collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableStateOf(UsageTab.OVERVIEW) }
    var range by rememberSaveable { mutableStateOf(UsageRange.THIRTY) }

    val summary = remember(sessions, messages, entries, range) {
        buildSummary(sessions, messages, entries, range)
    }
    val modelSummaries = remember(entries, range) { buildModelSummary(entries, range) }
    val filteredEntries = remember(entries, range) {
        val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(range.days.toLong())
        entries.filter { it.timestamp >= start }.sortedByDescending { it.timestamp }
    }
    var monthlyBudget by rememberSaveable { mutableStateOf<Long?>(null) }
    var monthlyUsed by rememberSaveable { mutableStateOf<Long?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        monthlyBudget = UsageRepository.budget(context)
        monthlyUsed = UsageRepository.currentMonthTokens(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Analytics, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("本地用量统计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "刷新") }
        }
        BudgetBanner(monthlyBudget, monthlyUsed, onSetBudget = { value ->
            scope.launch {
                UsageRepository.setBudget(context, value)
                monthlyBudget = UsageRepository.budget(context)
                monthlyUsed = UsageRepository.currentMonthTokens(context)
            }
        })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UsageTab.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = tab == item,
                    onClick = { tab = item },
                    shape = SegmentedButtonDefaults.itemShape(index, UsageTab.entries.size),
                    label = { Text(item.title) }
                )
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UsageRange.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = range == item,
                    onClick = { range = item },
                    shape = SegmentedButtonDefaults.itemShape(index, UsageRange.entries.size),
                    label = { Text(item.title) }
                )
            }
        }
        when (tab) {
            UsageTab.OVERVIEW -> OverviewTab(summary)
            UsageTab.MODELS -> ModelsTab(modelSummaries)
            UsageTab.DETAILS -> DetailsTab(filteredEntries)
        }
    }
}

@Composable
private fun OverviewTab(summary: UsageSummary) {
    Text("${summaryRangeLabel(summary)} 总览", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatCell("会话", summary.sessions.toString(), Modifier.weight(1f))
        StatCell("消息", summary.messages.toString(), Modifier.weight(1f))
        StatCell("Token", formatCount(summary.totalTokens), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatCell("活跃天", summary.activeDays.toString(), Modifier.weight(1f))
        StatCell("连续使用", "${summary.currentStreak}天", Modifier.weight(1f))
        StatCell("最长记录", "${summary.longestStreak}天", Modifier.weight(1f))
    }
    ChartSection("每日 Token 趋势") {
        TokenBarChart(summary.dailyTokens)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("使用统计", style = MaterialTheme.typography.titleSmall)
            Text("连续使用 ${summary.currentStreak} 天", style = MaterialTheme.typography.bodySmall)
            Text("最长记录 ${summary.longestStreak} 天", style = MaterialTheme.typography.bodySmall)
            Text("高峰时段 ${summary.peakHour?.let { String.format(Locale.getDefault(), "%02d:00", it) } ?: "暂无"}", style = MaterialTheme.typography.bodySmall)
            Text("最爱模型 ${summary.favoriteModel.ifBlank { "暂无" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
    ChartSection("活跃日历") {
        ActivityHeatmap(summary.dailyTokensByDate)
        Text("过去 ${summary.dailyTokens.size} 天，共 ${summary.activeDates.size} 天活跃", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelsTab(models: List<ModelSummary>) {
    if (models.isEmpty()) {
        EmptyUsage("还没有模型用量记录，完成一次模型任务后会显示排行。")
        return
    }
    ChartSection("模型使用占比") {
        TokenBarChart(models.map { it.total })
    }
    Text("模型排行", style = MaterialTheme.typography.titleSmall)
    models.forEachIndexed { index, item ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(modelColor(index), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text(item.model, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(item.percentage * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
                Text("${formatCount(item.input)} in · ${formatCount(item.output)} out", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.LinearProgressIndicator(progress = { item.percentage }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(10.dp)) {
        Text("建议：长文本任务可优先使用 DeepSeek，实际成本以服务商账单为准。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailsTab(entries: List<UsageEntry>) {
    if (entries.isEmpty()) {
        EmptyUsage("还没有用量明细。")
        return
    }
    val formatter = remember { DateTimeFormatter.ofPattern("MM/dd HH:mm") }
    entries.take(100).forEach { entry ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.model, style = MaterialTheme.typography.bodyMedium)
                Text("${entry.provider.displayName} · ${formatInstant(entry.timestamp, formatter)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatCount(entry.totalTokens), style = MaterialTheme.typography.bodyMedium)
                Text("in ${formatCount(entry.inputTokens.toLong())} / out ${formatCount(entry.outputTokens.toLong())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BudgetBanner(budget: Long?, used: Long?, onSetBudget: (Long) -> Unit) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("月度预算", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (budget == null || budget <= 0L) "未设置" else "已用 ${formatCount(used ?: 0L)} / ${formatCount(budget)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (budget != null && budget > 0L) {
                val fraction = ((used ?: 0L).toFloat() / budget.toFloat()).coerceIn(0f, 1f)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        fraction >= 0.9f -> MaterialTheme.colorScheme.error
                        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
            Button(onClick = { showDialog = true }) { Text("设置预算") }
        }
    }
    if (showDialog) {
        var input by remember { mutableStateOf((budget ?: 0L).takeIf { it > 0 }?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("设置月度 Token 预算") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("达到预算后用量页会高亮提醒。填 0 表示不设预算。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = input, onValueChange = { input = it.filter { c -> c.isDigit() } }, label = { Text("Token 数") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSetBudget(input.toLongOrNull() ?: 0L)
                    showDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ChartSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) { content() }
        }
    }
}

@Composable
private fun TokenBarChart(values: List<Long>) {
    val primary = MaterialTheme.colorScheme.primary
    if (values.all { it == 0L }) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("暂无 Token 数据", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val slot = size.width / values.size.coerceAtLeast(1)
        val barWidth = (slot * 0.64f).coerceAtLeast(3f)
        values.forEachIndexed { index, value ->
            val height = size.height * (value.toFloat() / maxValue.toFloat())
            drawRoundRect(
                color = primary.copy(alpha = 0.84f),
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                size = Size(barWidth, height.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

@Composable
private fun ActivityHeatmap(dailyTokens: Map<LocalDate, Long>) {
    val today = LocalDate.now()
    val firstTracked = dailyTokens.keys.minOrNull() ?: today.minusDays(29)
    val lastTracked = dailyTokens.keys.maxOrNull() ?: today
    val calendarStart = firstTracked.minusDays((firstTracked.dayOfWeek.value - 1).toLong())
    val calendarEnd = lastTracked.plusDays((7 - lastTracked.dayOfWeek.value).toLong())
    val days = generateSequence(calendarStart) { date ->
        date.plusDays(1).takeIf { it <= calendarEnd }
    }.toList()
    var selectedDateText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDate = selectedDateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val monthText = if (calendarStart.year == calendarEnd.year && calendarStart.month == calendarEnd.month) {
        "${calendarStart.year}年${calendarStart.monthValue}月"
    } else {
        "${calendarStart.year}年${calendarStart.monthValue}月 – ${calendarEnd.year}年${calendarEnd.monthValue}月"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(monthText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (index) {
                        5 -> MaterialTheme.colorScheme.primary
                        6 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { date ->
                        val inRange = date >= firstTracked && date <= lastTracked
                        val tokens = dailyTokens[date] ?: 0L
                        val intensity = if (tokens <= 0L) 0f else (tokens.toFloat() / (dailyTokens.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L)).coerceIn(.18f, 1f)
                        val isToday = date == today
                        val isSelected = date == selectedDate
                        Box(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .background(
                                        if (!inRange) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f)
                                        else if (tokens > 0) MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(5.dp)
                                    )
                                    .then(
                                        if (isToday || isSelected) Modifier.border(
                                            width = if (isToday) 2.dp else 1.dp,
                                            color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(5.dp)
                                        ) else Modifier
                                    )
                                    .clickable { selectedDateText = date.toString() },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (!inRange) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
                                    else if (date.dayOfWeek.value >= 6) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = isSelected,
                                onDismissRequest = { selectedDateText = null }
                            ) {
                                Text(
                                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    "Token：${formatCount(tokens)}",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyUsage(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

private fun buildSummary(sessions: List<Session>, messages: List<Message>, entries: List<UsageEntry>, range: UsageRange): UsageSummary {
    val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(range.days.toLong())
    val filteredSessions = sessions.filter { it.createdAt >= start }
    val filteredMessages = messages.filter { it.timestamp >= start }
    val filteredEntries = entries.filter { it.timestamp >= start }
    val activeDates = (filteredMessages.map { localDate(it.timestamp) } + filteredEntries.map { localDate(it.timestamp) }).toSet()
    val dailyTokens = (0 until range.days).map { offset ->
        val date = LocalDate.now().minusDays((range.days - 1 - offset).toLong())
        filteredEntries.filter { localDate(it.timestamp) == date }.sumOf { it.totalTokens }
    }
    val dailyTokensByDate = (0 until range.days).associate { offset ->
        val date = LocalDate.now().minusDays((range.days - 1 - offset).toLong())
        date to filteredEntries.filter { localDate(it.timestamp) == date }.sumOf { it.totalTokens }
    }
    val hour = filteredEntries.groupingBy { localHour(it.timestamp) }.eachCount().maxByOrNull { it.value }?.key
    val favorite = filteredEntries.groupingBy { it.model }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    val streaks = streaks(activeDates)
    return UsageSummary(filteredSessions.size, filteredMessages.size, filteredEntries.sumOf { it.totalTokens }, activeDates.size, streaks.first, streaks.second, hour, favorite, dailyTokens, activeDates, dailyTokensByDate)
}

private fun buildModelSummary(entries: List<UsageEntry>, range: UsageRange): List<ModelSummary> {
    val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(range.days.toLong())
    val groups = entries.filter { it.timestamp >= start }.groupBy { it.model }
    val total = groups.values.sumOf { list -> list.sumOf { it.totalTokens } }.toFloat().coerceAtLeast(1f)
    return groups.map { (model, list) ->
        val input = list.sumOf { it.inputTokens.toLong() }
        val output = list.sumOf { it.outputTokens.toLong() }
        val amount = list.sumOf { it.totalTokens }
        ModelSummary(model, input, output, amount, amount / total)
    }.sortedByDescending { it.total }
}

private fun streaks(dates: Set<LocalDate>): Pair<Int, Int> {
    if (dates.isEmpty()) return 0 to 0
    val sorted = dates.sorted()
    var current = 1
    var longest = 1
    sorted.zipWithNext().forEach { (a, b) ->
        if (a.plusDays(1) == b) current++ else current = 1
        longest = maxOf(longest, current)
    }
    var currentStreak = 0
    var cursor = LocalDate.now()
    while (cursor in dates) {
        currentStreak++
        cursor = cursor.minusDays(1)
    }
    return currentStreak to longest
}

private fun localDate(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
private fun localHour(timestamp: Long): Int = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).hour
private fun formatInstant(timestamp: Long, formatter: DateTimeFormatter): String = formatter.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
private fun summaryRangeLabel(summary: UsageSummary): String = "最近 ${summary.dailyTokens.size} 天"
private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
    value >= 1_000 -> "%.1fk".format(value / 1_000f)
    else -> value.toString()
}
private fun modelColor(index: Int): Color = listOf(Color(0xFF2D8CFF), Color(0xFF4CAF50), Color(0xFFFFA726), Color(0xFF8E6BC8), Color(0xFFE85D75))[index % 5]
