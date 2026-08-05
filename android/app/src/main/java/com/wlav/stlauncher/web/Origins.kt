package com.wlav.stlauncher.web

import android.net.Uri

/**
 * The `scheme://host[:port]` form of a URI, or `null` when either component is
 * missing. Comparing origins is how main-frame navigation is kept inside the
 * loopback endpoint the bridge was installed for.
 */
internal fun Uri?.originOrNull(): String? {
    this ?: return null
    val scheme = scheme ?: return null
    val host = host ?: return null
    return buildString {
        append(scheme)
        append("://")
        append(host)
        if (port != -1) {
            append(':')
            append(port)
        }
    }
}
