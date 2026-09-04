package com.mrgreenapps.a11ypilot

import com.mrgreenapps.a11ypilot.agent.BalanceProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceProbeTest {
    @Test
    fun parsesPinAiWalletBalanceInProviderCurrency() {
        val result = BalanceProbe.parsePinAi(
            """{"mode":"pay_as_you_go","balance":156.8,"remaining":154.2,"unit":"usd"}"""
        )

        assertEquals("156.8", result.amount)
        assertEquals("USD", result.currency)
    }

    @Test
    fun prefersWalletBalanceOverRemainingAndReadsUnit() {
        val result = BalanceProbe.parsePinAi(
            """{"balance":659.35145252,"remaining":650.0,"unit":"USD","isValid":true}"""
        )

        assertEquals("659.35145252", result.amount)
        assertEquals("USD", result.currency)
    }

    @Test
    fun parsesNestedPinAiBalanceAndDefaultsToUsd() {
        val result = BalanceProbe.parsePinAi(
            """{"data":{"balance":{"amount":"12.5000"}}}"""
        )

        assertEquals("12.5", result.amount)
        assertEquals("USD", result.currency)
    }

    @Test
    fun parsesDeepSeekCnyBalance() {
        val result = BalanceProbe.parseDeepSeek(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"23.4500","granted_balance":"3.45","topped_up_balance":"20.00"}]}"""
        )

        assertEquals("23.45", result.amount)
        assertEquals("CNY", result.currency)
    }
}
