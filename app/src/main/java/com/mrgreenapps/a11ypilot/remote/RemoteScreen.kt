package com.mrgreenapps.a11ypilot.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * 「远程指挥」界面：手机端指挥 Metis 桌面端。
 *
 * 两个阶段：
 *  1. 配对（DISCONNECTED / PAIRING）：扫码（预留 CameraX 接口）+ 手动输入兜底。
 *  2. 会话（CONNECTED / RUNNING）：状态、指令输入、事件流（文本流式 + 工具/状态标记）、权限审批。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    uiState: RemoteUiState,
    onConnect: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onAnswerPermission: (Boolean) -> Unit,
    onCancelRun: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scannedText by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    // 调起 ML Kit 全屏扫码页（GmsBarcodeScanning.startScan），结果回填 scannedText。
    val onScan: () -> Unit = {
        scanError = null
        val scanner = GmsBarcodeScanning.getClient(context)
        scanner.startScan()
            .addOnSuccessListener { result ->
                val value = result.rawValue
                if (value.isNullOrBlank()) {
                    scanError = "未识别到二维码，请重试"
                } else {
                    scannedText = value
                }
            }
            .addOnCanceledListener {
                scanError = "已取消扫码"
            }
            .addOnFailureListener { e ->
                scanError = "扫码失败：${e.message ?: "未知错误"}"
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程指挥") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (uiState.connectionState != RemoteConnectionState.DISCONNECTED) {
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.AutoMirrored.Filled.Logout, "断开连接")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (uiState.connectionState) {
                RemoteConnectionState.DISCONNECTED, RemoteConnectionState.PAIRING ->
                    PairingView(uiState, onConnect, scannedText, onScan, scanError)
                RemoteConnectionState.CONNECTED, RemoteConnectionState.RUNNING ->
                    SessionView(uiState, onSendCommand, onAnswerPermission, onCancelRun)
            }
        }
    }
}

/** 配对入口：说明 + 扫码 + 手动输入兜底。 */
@Composable
private fun PairingView(
    uiState: RemoteUiState,
    onConnect: (String) -> Unit,
    scannedText: String?,
    onScan: () -> Unit,
    scanError: String?
) {
    var input by remember { mutableStateOf("") }
    val pairing = uiState.connectionState == RemoteConnectionState.PAIRING

    // 扫码结果回填输入框（用户可再手动修改）。
    LaunchedEffect(scannedText) {
        if (scannedText != null) input = scannedText
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text("连接 Metis 桌面端", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "在桌面端生成配对二维码，扫码或手动输入连接地址完成配对。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        // 扫码入口：调起 ML Kit 全屏扫码页，结果回填输入框。
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫码配对")
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("连接地址 / 配对码") },
            placeholder = { Text("https://relay.example.com?code=…&token=…") },
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onConnect(input) },
            enabled = input.isNotBlank() && !pairing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (pairing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("连接")
            }
        }

        scanError?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        uiState.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 远程会话界面：状态 + 事件流 + 权限审批 + 指令输入。 */
@Composable
private fun SessionView(
    uiState: RemoteUiState,
    onSendCommand: (String) -> Unit,
    onAnswerPermission: (Boolean) -> Unit,
    onCancelRun: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val running = uiState.connectionState == RemoteConnectionState.RUNNING
    val listState = rememberLazyListState()
    // 文本增量之外的标记事件（工具调用 / 状态 / 权限 / 未知）。
    val markers = uiState.events.filterNot { it is MetisEvent.TextDelta }

    // 新事件到达时滚动到底部，保证流式输出始终可见。
    LaunchedEffect(uiState.events.size, uiState.assistantText.length) {
        if (uiState.events.isNotEmpty()) {
            listState.animateScrollToItem(uiState.events.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        StatusHeader(uiState)
        uiState.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (uiState.events.isEmpty() && !running) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "已连接。在下方输入指令，指挥桌面端执行。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 流式 assistant 文本：一个不断增长的块，避免每个 delta 一行。
                if (uiState.assistantText.isNotBlank()) {
                    item(key = "assistant-text") {
                        StreamingText(uiState.assistantText, running)
                    }
                }
                items(markers) { event ->
                    MarkerRow(event)
                }
            }
        }

        // 权限审批卡片：桌面端敏感操作前等待允许/拒绝。
        uiState.pendingPermission?.let { permission ->
            PermissionCard(permission, onAnswerPermission)
        }

        InputBar(
            input = input,
            running = running,
            onInputChange = { input = it },
            onSend = {
                onSendCommand(input.trim())
                input = ""
            },
            onCancel = onCancelRun
        )
    }
}

@Composable
private fun StatusHeader(uiState: RemoteUiState) {
    val (label, color) = when (uiState.connectionState) {
        RemoteConnectionState.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary
        RemoteConnectionState.RUNNING -> "运行中" to MaterialTheme.colorScheme.tertiary
        else -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = {},
            label = { Text(label) }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            uiState.endpoint?.baseUrl.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 流式文本块，运行中末尾加光标提示。 */
@Composable
private fun StreamingText(text: String, running: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text + if (running) "▌" else "",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 工具调用 / 状态 / 权限 / 未知事件的标记行。 */
@Composable
private fun MarkerRow(event: MetisEvent) {
    when (event) {
        is MetisEvent.ToolCall -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("工具 ${event.name}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is MetisEvent.Status -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (event.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = buildString {
                    append("状态")
                    if (event.status.isNotBlank()) append(" · ").append(event.status)
                    if (event.detail.isNotBlank()) append(" · ").append(event.detail)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        is MetisEvent.Unknown -> Text(
            "事件 ${event.type.ifBlank { "未知" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Unit
    }
}

/** 权限审批卡片。 */
@Composable
private fun PermissionCard(
    permission: MetisEvent.PermissionRequest,
    onAnswerPermission: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("权限请求", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (permission.tool.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("工具：${permission.tool}", style = MaterialTheme.typography.bodySmall)
            }
            if (permission.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(permission.summary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswerPermission(true) }) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("允许")
                }
                TextButton(onClick = { onAnswerPermission(false) }) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("拒绝")
                }
            }
        }
    }
}

/** 底部指令输入条。 */
@Composable
private fun InputBar(
    input: String,
    running: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发指令给桌面端") },
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                FilledIconButton(onClick = onCancel) {
                    Icon(Icons.Default.Stop, "停止", modifier = Modifier.size(20.dp))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
