package com.mrgreenapps.a11ypilot.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.mrgreenapps.a11ypilot.PilotAccessibilityService
import com.mrgreenapps.a11ypilot.EventLog
import com.mrgreenapps.a11ypilot.phoneuse.PhoneUseService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The single dispatch point for every tool call from either the in-app AgentEngine or the
 * MCP server. Resolves int IDs from the most recent screen snapshot, performs the action, waits
 * for the screen to settle, and returns a fresh snapshot in the result.
 *
 * Behavior is identical regardless of who invoked it.
 */
class ToolExecutor(
    private val appContext: Context,
    private val excludeOwnPackage: Boolean = false
) {

    @Volatile
    private var lastSnapshot: ScreenSerializer.Snapshot? = null

    sealed interface Result {
        data class Ok(
            val screen: String,
            val foregroundApp: String,
            val extra: String? = null,
            /** Base64-encoded JPEG/PNG bytes if the tool returned a screenshot. */
            val imageBase64: String? = null,
            val imageMimeType: String? = null,
            val imageWidth: Int = 0,
            val imageHeight: Int = 0
        ) : Result
        data class Err(val message: String) : Result
        data class Done(val success: Boolean, val summary: String) : Result
    }

    private fun service(): PilotAccessibilityService? =
        PilotAccessibilityService.INSTANCE
            ?: (PhoneUseService.getInstance() as? PilotAccessibilityService)

    private fun screenSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = wm.maximumWindowMetrics.bounds
        return m.width() to m.height()
    }

    private fun backgroundError(): String? = when {
        service() == null -> "PhoneUse 无障碍服务未连接"
        else -> null
    }

    private fun snapshotNow(): ScreenSerializer.Snapshot {
        val svc = service()
        val (w, h) = screenSize()
        val snap = ScreenSerializer.serialize(svc?.activeRoot(), w, h, excludeOwnPackage)
        lastSnapshot = snap
        return snap
    }

    private suspend fun snapshotAfter(): Result.Ok {
        // A few launchers publish the first accessibility event before their content tree is
        // attached. A short grace period avoids returning an empty screen to the model.
        delay(180)
        service()?.awaitSettle()
        val s = snapshotNow()
        return Result.Ok(s.text, s.foregroundPackage)
    }

    private fun requireService(): PilotAccessibilityService? {
        val s = service() ?: run {
            EventLog.append("tool: service not connected")
            return null
        }
        return s
    }

    private fun resolveId(id: Int): AccessibilityNodeInfo? {
        val node = lastSnapshot?.idMap?.get(id) ?: return null
        // The node may have been recycled by the framework since the snapshot was taken (e.g. the
        // screen changed under a long-lived MCP session). Guard against a stale/collected handle.
        return runCatching {
            if (node.isVisibleToUser || node.className != null || node.text != null || node.contentDescription != null) node else null
        }.getOrNull()
    }

    suspend fun dumpScreen(): Result {
        backgroundError()?.let { return Result.Err(it) }
        val s = snapshotNow()
        EventLog.append("agent> dump_screen → ${s.idMap.size} nodes")
        return Result.Ok(s.text, s.foregroundPackage)
    }

    /**
     * Returns only the lines that changed since the previous snapshot. Cheaper than a full
     * [dumpScreen] when the model just needs to confirm the effect of its last action.
     */
    suspend fun dumpDiff(): Result {
        backgroundError()?.let { return Result.Err(it) }
        val prev = lastSnapshot
        val s = snapshotNow()
        val delta = ScreenSerializer.diff(prev, s)
        EventLog.append("agent> dump_diff → ${delta.lineSequence().count()} lines")
        return Result.Ok(delta, s.foregroundPackage)
    }

    /** List interactive windows (id, active, focused, package) on the default display. */
    suspend fun listWindows(): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = service() as? PhoneUseService ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val text = svc.listWindows()
        EventLog.append("agent> list_windows")
        return Result.Ok(text, "system")
    }

    /** Dump the accessibility tree of a specific window by id (split-screen / dialogs). */
    suspend fun dumpWindow(windowId: Int): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = service() as? PhoneUseService ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val root = svc.rootForWindow(windowId) ?: return Result.Err("找不到窗口 id=$windowId（窗口可能已关闭）")
        val (w, h) = screenSize()
        val snap = ScreenSerializer.serialize(root, w, h, excludeOwnPackage)
        lastSnapshot = snap
        return Result.Ok(snap.text, snap.foregroundPackage)
    }

    suspend fun screenshot(maxEdgePx: Int = 1024, quality: Int = 70): Result {
        backgroundError()?.let { return Result.Err(it) }

        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return Result.Err("screenshot requires Android 11 (API 30)+")
        }
        val shot = svc.captureScreenshotJpegBase64(maxEdgePx, quality, Display.DEFAULT_DISPLAY)
            ?: return Result.Err("takeScreenshot failed (display may be secure or rate-limited)")
        val s = snapshotNow()
        EventLog.append("agent> screenshot → ${shot.width}x${shot.height} jpg ${shot.base64.length}b64")
        return Result.Ok(
            screen = s.text,
            foregroundApp = s.foregroundPackage,
            imageBase64 = shot.base64,
            imageMimeType = shot.mimeType,
            imageWidth = shot.width,
            imageHeight = shot.height
        )
    }

    suspend fun click(id: Int): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val node = resolveId(id) ?: return Result.Err("Unknown id $id (call dump_screen for fresh ids)")
        val target = if (supportsClick(node)) node else climbTo(node) { supportsClick(it) } ?: node
        var ok = runCatching {
            supportsClick(target) && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (!ok) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.isEmpty && node.isVisibleToUser) {
                val fallback = GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            Path().apply {
                                moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                lineTo(bounds.centerX().toFloat() + 1f, bounds.centerY().toFloat())
                            },
                            0,
                            60
                        )
                    )
                    .build()
                ok = dispatchAndAwait(svc, fallback)
                EventLog.append("agent> click [$id] fallback center=(${bounds.centerX()},${bounds.centerY()}) → $ok")
            }
        }
        EventLog.append(
            "agent> click [$id] → $ok class=${target.className?.toString()?.substringAfterLast('.')} " +
                "clickable=${target.isClickable} nodeBounds=${Rect().also { node.getBoundsInScreen(it) }}"
        )
        if (!ok) return Result.Err("performAction(CLICK) returned false on id $id")
        return snapshotAfter()
    }

    suspend fun longClick(id: Int): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val node = resolveId(id) ?: return Result.Err("Unknown id $id")
        val target = if (node.isLongClickable) node else climbTo(node) { it.isLongClickable } ?: node
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        EventLog.append("agent> long_click [$id] → $ok")
        if (!ok) return Result.Err("performAction(LONG_CLICK) returned false on id $id")
        return snapshotAfter()
    }

    suspend fun setText(id: Int, value: String): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val node = resolveId(id) ?: return Result.Err("Unknown id $id")
        if (!node.isEditable) return Result.Err("id $id is not editable")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        EventLog.append("agent> set_text [$id] = \"${value.take(30)}\" → $ok")
        if (!ok) return Result.Err("performAction(SET_TEXT) returned false on id $id")
        return snapshotAfter()
    }

    suspend fun scroll(id: Int, direction: String): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val node = resolveId(id) ?: return Result.Err("Unknown id $id")
        val target = if (node.isScrollable) node else climbTo(node) { it.isScrollable }
            ?: return Result.Err("No scrollable ancestor for id $id")
        val action = when (direction.lowercase()) {
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return Result.Err("direction must be up|down|left|right")
        }
        val ok = target.performAction(action)
        EventLog.append("agent> scroll [$id] $direction → $ok")
        if (!ok) return Result.Err("scroll returned false")
        return snapshotAfter()
    }

    suspend fun tap(x: Int, y: Int): Result {
        backgroundError()?.let { return Result.Err(it) }

        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val ok = dispatchAndAwait(svc, gesture)
        EventLog.append("agent> tap ($x,$y) → $ok")
        if (!ok) return Result.Err("tap dispatch failed")
        return snapshotAfter()
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Result {
        backgroundError()?.let { return Result.Err(it) }

        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 3000)))
            .build()
        val ok = dispatchAndAwait(svc, gesture)
        EventLog.append("agent> swipe ($x1,$y1)→($x2,$y2) ${durationMs}ms → $ok")
        if (!ok) return Result.Err("swipe dispatch failed")
        return snapshotAfter()
    }

    suspend fun global(action: String): Result {
        backgroundError()?.let { return Result.Err(it) }
        val svc = requireService() ?: return Result.Err("PhoneUse 无障碍服务未连接")
        val constant = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return Result.Err("unknown global action: $action")
        }
        val ok = svc.performGlobalAction(constant)
        EventLog.append("agent> global $action → $ok")
        if (!ok) return Result.Err("performGlobalAction returned false")
        return snapshotAfter()
    }

    suspend fun launchApp(pkg: String, background: Boolean = false): Result {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Result.Err("找不到可启动的应用：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launched = runCatching { appContext.startActivity(intent) }
            .onFailure { EventLog.append("agent> launch_app failed $pkg: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
        if (!launched) return Result.Err("无法在当前屏幕启动应用：$pkg")
        EventLog.append("agent> launch_app physical $pkg backgroundRequested=$background")
        return snapshotAfter()
    }

    suspend fun wait(ms: Int): Result {
        backgroundError()?.let { return Result.Err(it) }
        val clamped = ms.coerceIn(0, 3000)
        EventLog.append("agent> wait $clamped ms")
        kotlinx.coroutines.delay(clamped.toLong())
        val s = snapshotNow()
        return Result.Ok(s.text, s.foregroundPackage)
    }

    fun done(success: Boolean, summary: String): Result.Done {
        EventLog.append("agent> done success=$success: $summary")
        return Result.Done(success, summary)
    }

    private fun climbTo(
        start: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = start
        while (cur != null) {
            if (predicate(cur)) return cur
            cur = cur.parent
        }
        return null
    }

    private fun supportsClick(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    private suspend fun dispatchAndAwait(
        svc: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(g: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        val accepted = svc.dispatchGesture(gesture, cb, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }
}
