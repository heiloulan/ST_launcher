package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortAllocatorTest {
    @Test
    fun `allocates an available loopback port`() {
        val port = LoopbackPortAllocator().allocate()

        assertTrue(port in 1..65_535)
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { socket ->
            assertEquals(port, socket.localPort)
        }
    }
}
