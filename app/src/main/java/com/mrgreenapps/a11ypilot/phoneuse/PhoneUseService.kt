package com.mrgreenapps.a11ypilot.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mrgreenapps.a11ypilot.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * PhoneUse Accessibility Service - Advanced phone automation
 * Enables AI agent to control phone screen, launch apps, and perform automated actions
 */
/**
 * Shared accessibility implementation for PhoneUse.  The concrete service declared by the
 * application is [PilotAccessibilityService]; inheriting this class keeps a single Android
 * accessibility connection while allowing PhoneUseTool to use the automation API.
 */
open class PhoneUseService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneUseService"
        private val instance = AtomicReference<PhoneUseService?>(null)

        fun getInstance(): PhoneUseService? = instance.get()

        fun isRunning(): Boolean = instance.get() != null
    }

    override fun onCreate() {
        super.onCreate()
        instance.set(this)
        EventLog.append("phoneuse> service started")
    }

    override fun onServiceConnected() {
        // Some vendor ROMs ignore flags from the XML until the service reconnects. Reapply them
        // here so window roots, view ids and gestures are available consistently.
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 100
            }
        }.onFailure { EventLog.append("phoneuse> service info setup failed: ${it.message}") }
        EventLog.append("phoneuse> accessibility connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance.compareAndSet(this, null)
        EventLog.append("phoneuse> service stopped")
    }

    override fun onInterrupt() {
        EventLog.append("phoneuse> service interrupted")
    }

    // Concrete implementations (PilotAccessibilityService) override this; the base class only
    // satisfies the abstract contract. Event handling/logging lives in the subclass to avoid
    // double-logging every accessibility event.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    /** Returns the active root on the user's physical/default display. */
    fun activeRoot(): AccessibilityNodeInfo? {
        return runCatching { rootInActiveWindow }.getOrNull()
    }

    /** Returns the root of a specific window by id, or null if the window is gone. */
    fun rootForWindow(windowId: Int): AccessibilityNodeInfo? {
        return runCatching {
            windows.firstOrNull { it.id == windowId && it.displayId == Display.DEFAULT_DISPLAY }
                ?.let { runCatching { it.root }.getOrNull() }
        }.getOrNull()
    }

    /** Lists interactive windows on the default display as a compact string for the agent. */
    fun listWindows(): String {
        return runCatching {
            windows.filter { it.displayId == Display.DEFAULT_DISPLAY }.mapIndexed { index, window ->
                val root = runCatching { window.root }.getOrNull()
                "window[$index] id=${window.id} active=${window.isActive} focused=${window.isFocused} type=${window.type} pkg=${root?.packageName ?: "?"}"
            }.joinToString("\n")
        }.getOrElse { "windows unavailable: ${it.message}" }
    }

    /** Short diagnostic string used by the settings page and event log. */
    fun stateDescription(): String {
        val root = activeRoot()
        return if (root == null) {
            "服务已连接，当前没有可读的活动窗口"
        } else {
            "服务已连接 · ${root.packageName ?: "未知应用"}"
        }
    }

    /** Returns a compact diagnostic for the windows visible on the physical display. */
    fun windowDiagnostics(): String = runCatching {
        val entries = windows.filter { it.displayId == Display.DEFAULT_DISPLAY }.map { window ->
            val root = runCatching { window.root }.getOrNull()
            "display=${window.displayId}, active=${window.isActive}, focused=${window.isFocused}, " +
                "type=${window.type}, package=${root?.packageName ?: "?"}"
        }
        "physicalDisplayWindows=${entries.size}; " + entries.joinToString(" | ")
    }.getOrElse { "windows unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    /** Give a newly launched app a chance to publish its accessibility tree. */
    suspend fun awaitActiveRoot(timeoutMs: Long = 1800L): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            activeRoot()?.let { return it }
            delay(80)
        }
        val root = activeRoot()
        if (root == null) EventLog.append("phoneuse> no accessible window after ${timeoutMs}ms: ${windowDiagnostics()}")
        return root
    }

    /** Wait for a specific foreground package instead of accepting the previous Metis window. */
    suspend fun awaitPackageRoot(packageName: String, timeoutMs: Long = 5000L): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = activeRoot()
            if (root?.packageName?.toString() == packageName) return root
            delay(80)
        }
        val root = activeRoot()
        if (root?.packageName?.toString() != packageName) {
            EventLog.append(
                "phoneuse> package window timeout expected=$packageName actual=${root?.packageName ?: "none"}; " +
                    windowDiagnostics()
            )
        }
        return root?.takeIf { it.packageName?.toString() == packageName }
    }

    // ==================== Screen Interaction ====================

    /**
     * Click at specific coordinates
     */
    suspend fun tapAt(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                EventLog.append("phoneuse> tap at ($x, $y) completed")
                cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                EventLog.append("phoneuse> tap at ($x, $y) cancelled")
                cont.resume(false)
            }
        }, null)
    }

    /**
     * Swipe gesture
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300): Boolean =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> swipe completed")
                    cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> swipe cancelled")
                    cont.resume(false)
                }
            }, null)
        }

    /**
     * Long press at coordinates
     */
    suspend fun longPressAt(x: Float, y: Float, duration: Long = 1000): Boolean =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> long press completed")
                    cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> long press cancelled")
                    cont.resume(false)
                }
            }, null)
        }

    // ==================== Element Interaction ====================

    /**
     * Find node by text content
     */
    fun findNodeByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        val rootNode = activeRoot() ?: return null
        return findNodeByTextRecursive(rootNode, text, exactMatch)
    }

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        exactMatch: Boolean
    ): AccessibilityNodeInfo? {
        val labels = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString()
        )
        val match = labels.any { label ->
            if (exactMatch) label.trim().equals(text.trim(), ignoreCase = true)
            else label.contains(text, ignoreCase = true)
        }

        // Text is often exposed on a non-clickable label while its parent owns the action.
        // Return the label and let actionableParent climb to the actual target.
        if (match) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, text, exactMatch)
            if (result != null) return result
        }

        return null
    }

    /** 一个文本匹配候选，供联系人消歧使用。 */
    data class TextMatch(
        val text: String,
        val bounds: Rect,
        val clickable: Boolean
    )

    /**
     * 收集屏幕上**所有**文本匹配 [text] 的节点，而不是只返回第一个。
     *
     * 微信搜索结果会同时出现「联系人 / 群聊 / 聊天记录 / 公众号」多个分区，
     * 同名候选可能有多个；只点第一个极易进错人。这里把它们全部枚举出来，
     * 由调用方逐个尝试并在进入会话后校验标题栏。
     */
    fun findAllNodesByText(text: String, exactMatch: Boolean = false): List<TextMatch> {
        val rootNode = activeRoot() ?: return emptyList()
        val out = mutableListOf<TextMatch>()
        fun walk(node: AccessibilityNodeInfo) {
            val labels = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.hintText?.toString()
            )
            val hit = labels.any { label ->
                if (exactMatch) label.trim().equals(text.trim(), ignoreCase = true)
                else label.contains(text, ignoreCase = true)
            }
            if (hit) {
                val r = Rect()
                node.getBoundsInScreen(r)
                // 只保留屏幕上可见、有实际面积的候选，避免把 0 面积的容器也算进来
                if (!r.isEmpty && r.top >= 0) {
                    out += TextMatch(labels.firstOrNull().orEmpty().trim(), r, node.isClickable)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        runCatching { walk(rootNode) }
        return out
    }

    /**
     * 读取屏幕顶部区域的可见文本（微信聊天页的标题栏所在区域）。
     * 用于确认点击搜索结果后是否真的进入了目标联系人的会话，而不是群聊或聊天记录。
     */
    fun topBarTexts(fraction: Float = 0.18f): List<String> {
        val rootNode = activeRoot() ?: return emptyList()
        val screen = Rect()
        rootNode.getBoundsInScreen(screen)
        val limit = screen.top + (screen.height() * fraction).toInt()
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo) {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty && r.bottom <= limit) out += t
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        runCatching { walk(rootNode) }
        return out.distinct()
    }

    /**
     * Find node by view ID
     */
    fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val rootNode = activeRoot() ?: return null
        return findNodeByViewIdRecursive(rootNode, viewId)
    }

    private fun findNodeByViewIdRecursive(
        node: AccessibilityNodeInfo,
        viewId: String
    ): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(viewId) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByViewIdRecursive(child, viewId)
            if (result != null) return result
        }

        return null
    }

    /**
     * Click element by text
     */
    fun clickByText(text: String, exactMatch: Boolean = false): Boolean {
        val node = findNodeByText(text, exactMatch) ?: return false
        val target = actionableParent(node)
        val success = clickNodeWithFallback(node, target, "text='$text'")
        EventLog.append("phoneuse> click by text '$text': $success")
        return success
    }

    /**
     * Click element by view ID
     */
    fun clickByViewId(viewId: String): Boolean {
        val node = findNodeByViewId(viewId) ?: return false
        val target = actionableParent(node)
        val success = clickNodeWithFallback(node, target, "viewId='$viewId'")
        EventLog.append("phoneuse> click by viewId '$viewId': $success")
        return success
    }

    /**
     * Input text to focused element
     */
    fun inputText(text: String): Boolean {
        val root = activeRoot() ?: return false
        // Some WeChat/OEM screens expose the editor without assigning accessibility focus after
        // a row click. Prefer focus, but fall back to the only visible editable control so a
        // successful contact navigation does not look like a failed click to the agent.
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditable(root)
            ?: findVisibleEditable(root)
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val success = node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
            )
            EventLog.append("phoneuse> input text: $success")
            return success
        }

        return false
    }

    /** Set text on a specific visible editor, avoiding a stale search field after navigation. */
    fun inputTextByViewId(viewId: String, text: String): Boolean {
        // WeChat assigns `bkk` to both the editor's wrapper and its EditText child. Resolve the
        // editable descendant rather than stopping at the first matching container.
        val node = findEditableByViewId(viewId, activeRoot() ?: return false) ?: return false
        if (!node.isEditable || !node.isVisibleToUser) return false
        val success = runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
            )
        }.getOrDefault(false)
        EventLog.append("phoneuse> input text viewId='$viewId': $success")
        return success
    }

    private fun findEditableByViewId(viewId: String, node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(viewId) == true && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableByViewId(viewId, child)?.let { return it }
        }
        return null
    }

    fun pressImeEnter(): Boolean {
        val root = activeRoot() ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocusedEditable(root)
            ?: findVisibleEditable(root)
            ?: return false
        // ACTION_CLICK only re-focuses the editor on recent WeChat builds. Use the dedicated IME
        // action first, then retain the click fallback for search fields that do not publish it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ime = runCatching {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }.getOrDefault(false)
            if (ime) {
                EventLog.append("phoneuse> ime enter: true")
                return true
            }
        }
        val clicked = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        EventLog.append("phoneuse> ime enter fallback click: $clicked")
        return clicked
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && (node.isFocused || node.isAccessibilityFocused)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFocusedEditable(child)?.let { return it }
        }
        return null
    }

    private fun findVisibleEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.isEmpty) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findVisibleEditable(child)?.let { return it }
        }
        return null
    }

    fun hasVisibleEditable(): Boolean = activeRoot()?.let { findVisibleEditable(it) != null } == true

    fun hasVisibleViewId(viewId: String): Boolean = findNodeByViewId(viewId)?.let {
        it.isVisibleToUser && !Rect().also { bounds -> it.getBoundsInScreen(bounds) }.isEmpty
    } == true

    suspend fun awaitViewId(viewId: String, timeoutMs: Long = 2500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasVisibleViewId(viewId)) return true
            delay(80)
        }
        return hasVisibleViewId(viewId)
    }

    /** Bounds of the actionable row containing a semantic text label. */
    fun getActionTargetBounds(text: String, exactMatch: Boolean = false): Rect? {
        val node = findNodeByText(text, exactMatch) ?: return null
        val target = actionableParent(node)
        return Rect().also { target.getBoundsInScreen(it) }
    }

    private fun actionableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!supportsClick(current) && current.parent != null) {
            current = current.parent
        }
        return current
    }

    /**
     * WeChat and some OEM views expose a visible label without a click action. Try the
     * accessibility action first, then tap the label's physical bounds as a last resort.
     */
    private fun clickNodeWithFallback(
        node: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        description: String
    ): Boolean {
        val targetBounds = Rect().also { target.getBoundsInScreen(it) }
        val nodeBounds = Rect().also { node.getBoundsInScreen(it) }
        val actionSuccess = if (supportsClick(target)) {
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
        } else false
        if (actionSuccess) {
            EventLog.append(
                "phoneuse> click target $description action=true class=${target.className?.toString()?.substringAfterLast('.')} " +
                    "bounds=$targetBounds"
            )
            return true
        }

        val tapBounds = when {
            !targetBounds.isEmpty && targetBounds.width() <= 1400 && targetBounds.height() <= 600 -> targetBounds
            else -> nodeBounds
        }
        if (tapBounds.isEmpty || !node.isVisibleToUser) {
            EventLog.append("phoneuse> click target $description failed no_bounds target=$targetBounds node=$nodeBounds")
            return false
        }
        val x = tapBounds.centerX().toFloat()
        val y = tapBounds.centerY().toFloat()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val accepted = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> click target $description fallback completed at=(${tapBounds.centerX()},${tapBounds.centerY()})")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    EventLog.append("phoneuse> click target $description fallback cancelled at=(${tapBounds.centerX()},${tapBounds.centerY()})")
                }
            },
            null
        )
        EventLog.append(
            "phoneuse> click target $description action=false fallback_accepted=$accepted " +
                "clickable=${target.isClickable} actions=${target.actionList.joinToString { it.id.toString() }} " +
                "bounds=$tapBounds"
        )
        return accepted
    }

    private fun supportsClick(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    // ==================== System Actions ====================

    /**
     * Press back button
     */
    fun pressBack(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        EventLog.append("phoneuse> press back: $success")
        return success
    }

    /**
     * Press home button
     */
    fun pressHome(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        EventLog.append("phoneuse> press home: $success")
        return success
    }

    /**
     * Show recent apps
     */
    fun showRecentApps(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_RECENTS)
        EventLog.append("phoneuse> show recent apps: $success")
        return success
    }

    /**
     * Open notifications
     */
    fun openNotifications(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        EventLog.append("phoneuse> open notifications: $success")
        return success
    }

    /**
     * Open quick settings
     */
    fun openQuickSettings(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        EventLog.append("phoneuse> open quick settings: $success")
        return success
    }

    // ==================== App Control ====================

    /** Launch an app on the user's physical/default display. */
    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: run {
            EventLog.append("phoneuse> launch failed package=$packageName: no launch intent")
            return false
        }
        return runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            EventLog.append("phoneuse> launch physical package=$packageName")
            true
        }.getOrElse {
            EventLog.append("phoneuse> launch failed package=$packageName: ${it.message}")
            false
        }
    }

    /**
     * Open URL or deep link
     */
    fun openUrl(url: String): Boolean {
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            EventLog.append("phoneuse> open URL physical: $url")
            true
        }.getOrElse {
            EventLog.append("phoneuse> open URL failed: ${it.message}")
            false
        }
    }

    /**
     * Set alarm
     */
    suspend fun setAlarm(hour: Int, minute: Int, message: String = ""): Result<MetisAlarm> {
        val result = AlarmStore.schedule(this, hour, minute, message)
        result.onSuccess { alarm ->
            EventLog.append("phoneuse> set silent alarm id=${alarm.id}: $hour:$minute")
        }.onFailure { error ->
            EventLog.append("phoneuse> set silent alarm failed: ${error.message}")
        }
        return result
    }

    // ==================== Screen Analysis ====================

    /**
     * Get screen hierarchy as text
     */
    fun getScreenHierarchy(): String {
        val rootNode = activeRoot() ?: return "No active window"
        val builder = StringBuilder()
        buildHierarchy(rootNode, 0, builder)
        return builder.toString()
    }

    private fun buildHierarchy(node: AccessibilityNodeInfo, depth: Int, builder: StringBuilder) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.split(".")?.lastOrNull() ?: "Unknown"
        val viewId = node.viewIdResourceName ?: ""

        builder.append("$indent$className")
        if (viewId.isNotEmpty()) builder.append(" [$viewId]")
        if (text.isNotEmpty()) builder.append(" \"$text\"")
        if (desc.isNotEmpty()) builder.append(" ($desc)")
        if (node.isClickable) builder.append(" [clickable]")
        builder.append("\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            buildHierarchy(child, depth + 1, builder)
        }
    }

    /**
     * Get element bounds
     */
    fun getElementBounds(text: String): Rect? {
        val node = findNodeByText(text) ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }
}
