package com.wlav.stlauncher.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeSourcePolicyTest {
    @Test
    fun `only exact origin main frame messages are accepted`() {
        val expected = "http://127.0.0.1:18080"

        assertTrue(BridgeSourcePolicy.accepts(expected, expected, isMainFrame = true))
        assertFalse(
            BridgeSourcePolicy.accepts(
                expected,
                "http://127.0.0.1:18081",
                isMainFrame = true,
            ),
        )
        assertFalse(
            BridgeSourcePolicy.accepts(
                expected,
                "http://localhost:18080",
                isMainFrame = true,
            ),
        )
        assertFalse(BridgeSourcePolicy.accepts(expected, expected, isMainFrame = false))
    }

    @Test
    fun `bridge origin must be only a numeric loopback origin`() {
        assertTrue(BridgeSourcePolicy.isExactLoopbackOrigin("http://127.0.0.1:18080"))
        assertFalse(BridgeSourcePolicy.isExactLoopbackOrigin("https://127.0.0.1:18080"))
        assertFalse(BridgeSourcePolicy.isExactLoopbackOrigin("http://localhost:18080"))
        assertFalse(BridgeSourcePolicy.isExactLoopbackOrigin("http://user@127.0.0.1:18080"))
        assertFalse(BridgeSourcePolicy.isExactLoopbackOrigin("http://127.0.0.1:18080/path"))
        assertFalse(BridgeSourcePolicy.isExactLoopbackOrigin("http://127.0.0.1"))
    }
}
