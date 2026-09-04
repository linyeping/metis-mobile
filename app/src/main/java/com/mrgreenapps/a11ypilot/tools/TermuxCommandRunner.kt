package com.mrgreenapps.a11ypilot.tools

import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

class TermuxCommandRunner(private val context: Context) {
    enum class Capability(val displayName: String, val detail: String) {
        READY("可用", "RunCommandService 和命令权限已就绪"),
        NOT_INSTALLED("未安装", "没有检测到 Termux"),
        INCOMPATIBLE_BUILD(
            "版本不兼容",
            "当前 Termux 未导出 RunCommandService。Google Play 版不能被 Metis 调用；需要支持 RunCommandService 的官方 GitHub/F-Droid 版"
        ),
        PERMISSION_REQUIRED("缺少权限", "Metis 尚未获得 com.termux.permission.RUN_COMMAND"),
        INTERNAL_READY("内置可用", "Metis 内置轻量命令器，仅限应用私有工作目录和系统基础命令")
    }

    internal data class EnvironmentProbe(
        val installed: Boolean,
        val servicePresent: Boolean,
        val serviceExported: Boolean,
        val permissionDeclared: Boolean,
        val permissionGranted: Boolean
    )

    internal data class ResultPayload(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val internalError: Int,
        val errorMessage: String
    )

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun displayText(): String = buildString {
            append("退出码：").append(exitCode)
            if (stdout.isNotBlank()) append("\n标准输出：\n").append(stdout.trimEnd())
            if (stderr.isNotBlank()) append("\n错误输出：\n").append(stderr.trimEnd())
        }
    }

    suspend fun run(command: String): Result<CommandResult> {
        return runCatching {
            withTimeout(15_000) {
                if (diagnoseExternal() == Capability.READY) execute(command)
                else executeInternal(command)
            }
        }.recoverCatching { error ->
            val detail = if (error is TimeoutCancellationException) {
                "命令执行超过 15 秒，已停止进程。"
            } else {
                error.message.orEmpty()
            }
            throw IllegalStateException(
                "命令执行失败：$detail",
                error
            )
        }
    }

    fun diagnoseExternal(): Capability = classify(probeEnvironment())

    /** Reports the effective executor, including the built-in fallback. */
    fun diagnose(): Capability = diagnoseExternal().takeIf { it == Capability.READY } ?: Capability.INTERNAL_READY

    private suspend fun execute(command: String): CommandResult = suspendCancellableCoroutine { continuation ->
        val requestId = REQUEST_IDS.incrementAndGet()
        val action = "$RESULT_ACTION.$requestId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runCatching { this@TermuxCommandRunner.context.unregisterReceiver(this) }
                if (!continuation.isActive) return
                continuation.resume(parseResultIntent(intent))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }

        val callbackIntent = Intent(action).setPackage(context.packageName)
        val callback = PendingIntent.getBroadcast(
            context,
            requestId,
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val runIntent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_RESULT_PENDING_INTENT, callback)
        }
        try {
            context.startService(runIntent)
        } catch (error: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    private suspend fun executeInternal(command: String): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .start()
        // Drain stdout/stderr concurrently. Reading only after waitFor() deadlocks when the
        // command fills the OS pipe buffer (≈64KB): the child blocks on write, waitFor() times out,
        // and a fast, successful command is misreported as "timed out".
        val stdoutFuture = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        val stderrFuture = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { stdoutFuture.cancel(true) }
            runCatching { stderrFuture.cancel(true) }
            return@withContext CommandResult(124, "", "内置命令器超时（15 秒），只允许短任务")
        }
        val stdout = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        CommandResult(process.exitValue(), stdout, stderr)
    }

    @Suppress("DEPRECATION")
    private fun probeEnvironment(): EnvironmentProbe {
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return EnvironmentProbe(false, false, false, false, false)
        }

        val serviceInfo = runCatching {
            val component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_SERVICE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
            } else {
                context.packageManager.getServiceInfo(component, 0)
            }
        }.getOrNull()
        val permissionDeclared = packageInfo.permissions.orEmpty().any { it.name == RUN_COMMAND_PERMISSION }
        val permissionGranted = context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return EnvironmentProbe(
            installed = true,
            servicePresent = serviceInfo != null,
            serviceExported = serviceInfo?.exported == true,
            permissionDeclared = permissionDeclared,
            permissionGranted = permissionGranted
        )
    }

    companion object {
        private val REQUEST_IDS = AtomicInteger(4100)
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        internal const val EXTERNAL_ACCESS_SETUP_COMMAND =
            "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val RESULT_ACTION = "com.mrgreenapps.a11ypilot.TERMUX_RESULT"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        // RUN_COMMAND returns a Bundle under TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE.
        // The nested keys are deliberately short; these are the official Termux constants.
        internal const val EXTRA_RESULT_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        internal const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERROR = "err"
        private const val EXTRA_ERROR_MESSAGE = "errmsg"

        internal fun parseResultIntent(intent: Intent?): CommandResult {
            val result = intent?.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
                ?: return commandResult(null)
            return commandResult(
                ResultPayload(
                    exitCode = result.getInt(EXTRA_EXIT_CODE, -1),
                    stdout = result.getString(EXTRA_STDOUT).orEmpty(),
                    stderr = result.getString(EXTRA_STDERR).orEmpty(),
                    internalError = result.getInt(EXTRA_ERROR, 0),
                    errorMessage = result.getString(EXTRA_ERROR_MESSAGE).orEmpty()
                )
            )
        }

        internal fun commandResult(payload: ResultPayload?): CommandResult {
            if (payload == null) return CommandResult(-1, "", "Termux 未返回 result Bundle")
            return CommandResult(
                exitCode = payload.exitCode,
                stdout = payload.stdout,
                stderr = payload.stderr.ifBlank {
                    payload.errorMessage.ifBlank {
                        payload.internalError.takeIf { it != 0 }?.let { "Termux 内部错误：$it" }.orEmpty()
                    }
                }
            )
        }

        internal fun classify(probe: EnvironmentProbe): Capability = when {
            !probe.installed -> Capability.NOT_INSTALLED
            !probe.servicePresent || !probe.serviceExported || !probe.permissionDeclared -> Capability.INCOMPATIBLE_BUILD
            !probe.permissionGranted -> Capability.PERMISSION_REQUIRED
            else -> Capability.READY
        }
    }
}
