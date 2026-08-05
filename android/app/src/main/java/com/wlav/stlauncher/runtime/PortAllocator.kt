package com.wlav.stlauncher.runtime

import java.net.InetAddress
import java.net.ServerSocket

fun interface PortAllocator {
    fun allocate(): Int
}

class LoopbackPortAllocator : PortAllocator {
    override fun allocate(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
}
