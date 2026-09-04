package com.mrgreenapps.a11ypilot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.mrgreenapps.a11ypilot.phoneuse.PhoneUseService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class PilotAccessibilityService : PhoneUseService() {

    companion object {
        const val TAG = "AccessTest"

        @Volatile
        var INSTANCE: PilotAccessibilityService? = null
            private set
    }

    @Volatile
    private var lastEventTimeMs: Long = 0L

    // Event logging is throttled: during animations a single TYPE_WINDOW_CONTENT_CHANGED can fire
    // hundreds of times per second, and every EventLog.append copies the whole ring buffer and
    // re-emits a StateFlow (triggering UI recomposition). Only state transitions and sparse
    // content changes are recorded; `lastEventTimeMs` is still updated on every event so
    // awaitSettle() keeps working.
    private var lastLoggedContentChangeMs: Long = 0L

    /**
     * Suspends until no AccessibilityEvent has been received for [quietMs], or until [timeoutMs]
     * elapses, whichever comes first. Used by the agent loop to wait for a screen to "settle"
     * after dispatching an action.
     */
    /**
     * Captures the default-display screenshot via [takeScreenshot] (API 30+), downscales to
     * [maxEdgePx] on the longest edge, encodes JPEG at [quality], and returns base64 (no padding).
     * Returns null on older OS versions or if the platform call fails.
     */
    data class Screenshot(val base64: String, val mimeType: String, val width: Int, val height: Int)

    suspend fun captureScreenshotJpegBase64(
        maxEdgePx: Int = 1024,
        quality: Int = 70,
        displayId: Int = Display.DEFAULT_DISPLAY
    ): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val raw: Bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            val executor = Executors.newSingleThreadExecutor()
            cont.invokeOnCancellation { executor.shutdown() }
            try {
                takeScreenshot(
                    displayId,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val buffer: HardwareBuffer = screenshot.hardwareBuffer
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            } catch (t: Throwable) {
                                EventLog.append("screenshot: wrap failed ${t.message}")
                                null
                            } finally {
                                try { buffer.close() } catch (_: Throwable) {}
                                executor.shutdown()
                            }
                            if (cont.isActive) cont.resume(bmp)
                        }
                        override fun onFailure(errorCode: Int) {
                            EventLog.append("screenshot: onFailure code=$errorCode")
                            executor.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                executor.shutdown()
                EventLog.append("screenshot: takeScreenshot threw ${t.message}")
                if (cont.isActive) cont.resume(null)
            }
        } ?: return null

        // wrapHardwareBuffer's bitmap shares the hardware buffer; copy to a software bitmap so
        // we can downscale + compress safely, then recycle the hardware-backed wrapper.
        val software = try {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            EventLog.append("screenshot: copy failed ${t.message}")
            null
        } ?: run {
            try { raw.recycle() } catch (_: Throwable) {}
            return null
        }
        try { raw.recycle() } catch (_: Throwable) {}

        val w0 = software.width
        val h0 = software.height
        val longest = maxOf(w0, h0)
        val scaled = if (longest <= maxEdgePx) software else {
            val scale = maxEdgePx.toFloat() / longest
            val newW = (w0 * scale).toInt().coerceAtLeast(1)
            val newH = (h0 * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(software, newW, newH, true).also {
                if (it !== software) software.recycle()
            }
        }

        val finalW = scaled.width
        val finalH = scaled.height
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), baos)
        scaled.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return Screenshot(b64, "image/jpeg", finalW, finalH)
    }

    suspend fun awaitSettle(timeoutMs: Long = 1500L, quietMs: Long = 250L) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        // Poll-based: cheap and reliable; we already update lastEventTimeMs on every event.
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now >= deadline) return
            val sinceLast = now - lastEventTimeMs
            if (sinceLast >= quietMs) return
            val sleep = minOf(quietMs - sinceLast, deadline - now)
            if (sleep <= 0) return
            delay(sleep)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE = this
        ServiceState.setEnabled(true)
        EventLog.append("service: connected")
        Log.i(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ServiceState.setEnabled(false)
        EventLog.append("service: unbound")
        Log.i(TAG, "service unbound")
        OverlayController.hide()
        if (INSTANCE === this) INSTANCE = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ServiceState.setEnabled(false)
        EventLog.append("service: destroyed")
        if (INSTANCE === this) INSTANCE = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        EventLog.append("service: interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventTimeMs = SystemClock.uptimeMillis()
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        val pkg = event.packageName?.toString() ?: "?"

        // Throttle logging: only window-state changes and content changes at most once per 300ms
        // are written to the event log. All events still advance `lastEventTimeMs` above.
        val shouldLog = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                SystemClock.uptimeMillis() - lastLoggedContentChangeMs >= 300)
        if (shouldLog) {
            val cls = event.className?.toString() ?: "?"
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                lastLoggedContentChangeMs = SystemClock.uptimeMillis()
            }
            Log.d(TAG, "$type  $pkg  $cls")
            EventLog.append("$type  $pkg  $cls")
        }
        OverlayController.update("$type\n$pkg")
    }
}
