package com.wlav.stlauncher.web

import android.annotation.SuppressLint
import android.webkit.WebSettings

@SuppressLint("SetJavaScriptEnabled")
fun WebSettings.applyLauncherSecurityPolicy(launcherVersion: String) {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    safeBrowsingEnabled = true
    userAgentString = "$userAgentString STLauncher/$launcherVersion"
}
