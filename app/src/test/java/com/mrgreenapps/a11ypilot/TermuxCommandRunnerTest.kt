package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.tools.TermuxCommandRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxCommandRunnerTest {
    @Test
    fun missingRunCommandServiceIsReportedAsIncompatible() {
        val result = TermuxCommandRunner.classify(
            TermuxCommandRunner.EnvironmentProbe(
                installed = true,
                servicePresent = false,
                serviceExported = false,
                permissionDeclared = false,
                permissionGranted = false
            )
        )
        assertEquals(TermuxCommandRunner.Capability.INCOMPATIBLE_BUILD, result)
    }

    @Test
    fun compatibleEnvironmentIsReady() {
        val result = TermuxCommandRunner.classify(
            TermuxCommandRunner.EnvironmentProbe(
                installed = true,
                servicePresent = true,
                serviceExported = true,
                permissionDeclared = true,
                permissionGranted = true
            )
        )
        assertEquals(TermuxCommandRunner.Capability.READY, result)
    }

    @Test
    fun mapsOfficialResultPayload() {
        val result = TermuxCommandRunner.commandResult(
            TermuxCommandRunner.ResultPayload(
                exitCode = 0,
                stdout = "hello\n",
                stderr = "",
                internalError = 0,
                errorMessage = ""
            )
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(
            "com.termux.RUN_COMMAND_PENDING_INTENT",
            TermuxCommandRunner.EXTRA_RESULT_PENDING_INTENT
        )
        assertEquals("result", TermuxCommandRunner.EXTRA_PLUGIN_RESULT_BUNDLE)
    }

    @Test
    fun missingResultBundleIsReported() {
        val result = TermuxCommandRunner.commandResult(null)

        assertEquals(-1, result.exitCode)
        assertEquals("Termux 未返回 result Bundle", result.stderr)
    }
}
