package com.mrgreenapps.a11ypilot.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlarm
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mrgreenapps.a11ypilot.agent.AutomationScheduler
import com.mrgreenapps.a11ypilot.data.AutomationRepository
import com.mrgreenapps.a11ypilot.data.AutomationTask
import com.mrgreenapps.a11ypilot.data.AutomationTrigger
import com.mrgreenapps.a11ypilot.data.ModelCatalog
import com.mrgreenapps.a11ypilot.data.ModelProvider
import com.mrgreenapps.a11ypilot.data.ReasoningCatalog
import com.mrgreenapps.a11ypilot.data.ReasoningIntensity
import com.mrgreenapps.a11ypilot.data.SafetyLevel
import com.mrgreenapps.a11ypilot.data.WorkMode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID

/**
 * 自动化任务列表页：顶部「添加任务」按钮 + 已设定任务卡片列表（每条独立操作）。
 * - 空态：未设定任何任务时给引导与单一 CTA
 * - 操作：点击整张卡片进入编辑；右侧 Switch 即时启停；删除走二次确认
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(
    context: Context,
    onAddTask: () -> Unit,
    onEditTask: (String) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = remember { AutomationRepository(context) }
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("自动化任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 顶部：标题 + 「添加任务」主按钮（即使空态也始终可见，让用户能直接新建）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (tasks.isEmpty())
                            "还没有任何自动化任务"
                        else
                            "已设定 ${tasks.size} 个自动化任务",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (tasks.isEmpty())
                            "点击「添加任务」创建一个定时任务"
                        else
                            "点击下方任意任务进入编辑；新建请用右侧按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onAddTask) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加任务")
                }
            }
            HorizontalDivider()
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Alarm,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Text("还没有自动化任务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "点击右上角「添加任务」按钮，设定触发时间、执行模式与模型即可。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        AutomationTaskCard(
                            task = task,
                            onClick = { onEditTask(task.id) },
                            onToggle = { enabled ->
                                scope.launch {
                                    val updated = task.copy(enabled = enabled)
                                    repository.upsert(updated)
                                    if (enabled) {
                                        val scheduled = AutomationScheduler.schedule(context, updated)
                                        onNotice(
                                            if (scheduled.nextRunAt > 0)
                                                "已启用「${task.name}」，下次执行 ${formatRunTime(scheduled)}"
                                            else
                                                "「${task.name}」已启用，下一次执行时间待系统计算"
                                        )
                                    } else {
                                        AutomationScheduler.cancel(context, task.id)
                                        onNotice("已停用「${task.name}」")
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    AutomationScheduler.cancel(context, task.id)
                                    repository.deleteTask(task.id)
                                    onNotice("已删除自动化：${task.name}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationTaskCard(
    task: AutomationTask,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除自动化？") },
            text = {
                Text(
                    "「${task.name}」将被删除，此操作不可撤销。已计划的闹钟也会一并取消。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (task.enabled) Icons.Default.PlayArrow else Icons.Default.Alarm,
                    null,
                    tint = if (task.enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    task.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = task.enabled, onCheckedChange = onToggle)
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, "删除自动化", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "${task.trigger.labelZh()} · ${task.hour.toString().padStart(2, '0')}:${task.minute.toString().padStart(2, '0')} · ${task.mode.titleZhForAutomation()} · ${task.provider.displayName} · ${task.model}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "下次执行：${if (task.nextRunAt > 0) formatRunTime(task) else "未安排"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (task.lastRunAt != null && task.lastRunAt > 0) {
                Text(
                    text = "上次执行：${formatTimestamp(task.lastRunAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(Modifier.padding(top = 3.dp))
            Text(
                task.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

/**
 * 自动化任务编辑/新建页：从列表点击「添加任务」或某张卡片进入。
 * - taskId == null  → 新建模式
 * - taskId != null  → 编辑模式（启动时从仓库读出原值回填）
 * - onSaved 触发后回到列表，repository 写入 + 闹钟注册在保存那一刻完成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationEditScreen(
    context: Context,
    taskId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = remember { AutomationRepository(context) }
    val scope = rememberCoroutineScope()
    val isNew = taskId == null

    var existing by remember(taskId) { mutableStateOf<AutomationTask?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(taskId) {
        if (taskId != null) {
            existing = repository.getTask(taskId)
            if (existing == null) loadFailed = true
        }
    }

    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(AutomationTrigger.DAILY) }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }
    var dayValue by remember { mutableStateOf("1") }
    var mode by remember { mutableStateOf(WorkMode.COWORK) }
    var provider by remember { mutableStateOf(ModelProvider.CUSTOM_OPENAI) }
    var model by remember { mutableStateOf(ModelCatalog.defaultFor(provider)) }
    var reasoning by remember { mutableStateOf(ReasoningCatalog.defaultFor(provider, model)) }
    var initialized by remember { mutableStateOf(false) }

    // 编辑模式：等仓库读出原值后一次性回填
    LaunchedEffect(existing) {
        existing?.let { task ->
            name = task.name
            prompt = task.prompt
            trigger = task.trigger
            hour = task.hour.toString()
            minute = task.minute.toString()
            dayValue = if (trigger == AutomationTrigger.WEEKLY) {
                task.dayOfWeek.toString()
            } else if (trigger == AutomationTrigger.MONTHLY || trigger == AutomationTrigger.YEARLY) {
                task.dayOfMonth.toString()
            } else {
                task.dayOfWeek.toString()
            }
            mode = task.mode
            provider = task.provider
            model = task.model
            reasoning = task.reasoningIntensity
            initialized = true
        }
    }

    val models = ModelCatalog.forProvider(provider).filterNot { it.startsWith("claude") }
    val reasoningOptions = ReasoningCatalog.forModel(provider, model)

    LaunchedEffect(provider) {
        if (initialized) {
            model = ModelCatalog.defaultFor(provider)
            reasoning = ReasoningCatalog.defaultFor(provider, model)
        }
    }
    LaunchedEffect(model) {
        reasoning = ReasoningCatalog.normalize(provider, model, reasoning)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建自动化任务" else "编辑自动化任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (loadFailed) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(28.dp)
                ) {
                    Text("找不到该任务，可能已被删除", style = MaterialTheme.typography.titleMedium)
                    Text("返回列表后再试一次。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onBack) { Text("返回列表") }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle(
                "基础信息",
                "为这个自动化任务起个名字，告诉代理它应该做什么"
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("自动化任务名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("项目说明 / 告诉代理它应该做什么") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                minLines = 4,
                maxLines = 8
            )

            SectionTitle(
                "触发时间",
                "选择一次性 / 每小时 / 每天 / 每周 / 每月 / 每年"
            )
            SelectorField(
                label = "计划或触发器",
                selected = trigger.labelZh(),
                options = AutomationTrigger.entries,
                optionLabel = { it.labelZh() },
                onSelected = { trigger = it }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                    label = { Text("小时") },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(":", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = minute,
                    onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                    label = { Text("分钟") },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                when (trigger) {
                    AutomationTrigger.WEEKLY -> {
                        OutlinedTextField(
                            value = dayValue,
                            onValueChange = { dayValue = it.filter(Char::isDigit).take(2) },
                            label = { Text("星期(1-7)") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    AutomationTrigger.MONTHLY, AutomationTrigger.YEARLY -> {
                        OutlinedTextField(
                            value = dayValue,
                            onValueChange = { dayValue = it.filter(Char::isDigit).take(2) },
                            label = { Text("日期") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    else -> Unit
                }
            }

            SectionTitle(
                "执行",
                "选择代理的执行模式与底层模型"
            )
            SelectorField(
                label = "执行模式",
                selected = mode.titleZhForAutomation(),
                options = WorkMode.entries,
                optionLabel = { it.titleZhForAutomation() },
                onSelected = { mode = it }
            )
            SelectorField(
                label = "执行模型",
                selected = "${provider.displayName} · $model",
                options = ModelProvider.entries.filterNot { it == ModelProvider.CUSTOM_CLAUDE },
                optionLabel = { it.displayName },
                onSelected = { provider = it }
            )
            SelectorField(
                label = "推理强度",
                selected = reasoning.apiValue,
                options = reasoningOptions.ifEmpty { listOf(ReasoningIntensity.MEDIUM) },
                optionLabel = { it.apiValue },
                onSelected = { reasoning = it }
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    val parsedHour = hour.toIntOrNull()
                    val parsedMinute = minute.toIntOrNull()
                    if (name.isBlank() || prompt.isBlank()) {
                        onNotice("请填写任务名称和项目说明")
                    } else if (parsedHour !in 0..23 || parsedMinute !in 0..59) {
                        onNotice("时间必须在 00:00 到 23:59 之间")
                    } else {
                        scope.launch {
                            val targetId = existing?.id ?: UUID.randomUUID().toString()
                            val parsedDayWeek = dayValue.toIntOrNull()?.coerceIn(1, 7) ?: Calendar.MONDAY
                            val parsedDayMonth = dayValue.toIntOrNull()?.coerceIn(1, 31) ?: 1
                            val parsedMonth = existing?.month ?: 1
                            val baseTask = AutomationTask(
                                id = targetId,
                                name = name,
                                prompt = prompt,
                                trigger = trigger,
                                hour = parsedHour ?: 9,
                                minute = parsedMinute ?: 0,
                                dayOfWeek = parsedDayWeek,
                                dayOfMonth = parsedDayMonth,
                                month = parsedMonth,
                                mode = mode,
                                provider = provider,
                                model = model,
                                reasoningIntensity = reasoning,
                                safetyLevel = SafetyLevel.BALANCED,
                                enabled = existing?.enabled ?: true,
                                sessionId = existing?.sessionId
                            )
                            repository.upsert(baseTask)
                            val scheduled = AutomationScheduler.schedule(context, baseTask)
                            onNotice(
                                if (isNew) "自动化「${scheduled.name}」已创建，${formatRunTime(scheduled)} 执行"
                                else "已保存修改，${formatRunTime(scheduled)} 执行"
                            )
                            onSaved()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(if (isNew) Icons.Default.AddAlarm else Icons.Default.Save, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isNew) "创建任务" else "保存修改")
            }

            FilledTonalButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("取消")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun AutomationTrigger.labelZh(): String = when (this) {
    AutomationTrigger.ONCE -> "一次"
    AutomationTrigger.HOURLY -> "每小时"
    AutomationTrigger.DAILY -> "每天"
    AutomationTrigger.WEEKDAYS -> "工作日"
    AutomationTrigger.WEEKLY -> "每周"
    AutomationTrigger.MONTHLY -> "每月"
    AutomationTrigger.YEARLY -> "每年"
}

private fun WorkMode.titleZhForAutomation(): String = when (this) {
    WorkMode.CHAT -> "聊天"
    WorkMode.COWORK -> "协作"
    WorkMode.CODE -> "编程"
}

private fun formatRunTime(task: AutomationTask): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.nextRunAt))

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun SectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SelectorField(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}
