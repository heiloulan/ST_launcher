package com.wlav.stlauncher.web

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewSecurityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            webView?.destroy()
            webView = null
        }
        scope.cancel()
    }

    @Test
    fun securityPolicyDisablesFileAndMixedContentAccess() {
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext).also { webView = it }
            view.settings.applyLauncherSecurityPolicy("test")

            assertTrue(view.settings.javaScriptEnabled)
            assertTrue(view.settings.domStorageEnabled)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertTrue(
                view.settings.mixedContentMode == WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            )
            assertTrue(view.settings.userAgentString.endsWith(" STLauncher/test"))
        }
    }

    @Test
    fun bridgeRejectsEveryNonLoopbackOrigin() {
        instrumentation.runOnMainSync {
            val bridge = createBridge()
            val error = runCatching { bridge.install("https://example.com") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun bridgeCanBeScopedToOneExactLoopbackOrigin() {
        assumeTrue(
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
        )
        instrumentation.runOnMainSync {
            val bridge = createBridge()
            bridge.install("http://127.0.0.1:18080")
            bridge.remove()
        }
    }

    private fun createBridge(): SecureWebBridge {
        val view =
            webView
                ?: WebView(instrumentation.targetContext).also { webView = it }
        return SecureWebBridge(
            webView = view,
            scope = scope,
            blobStore = BlobDownloadStore(instrumentation.targetContext.cacheDir, reserveBytes = 0),
            scriptSource = "void 0;",
            callbacks =
                object : SecureWebBridge.Callbacks {
                    override fun onThemeColor(value: String) = Unit

                    override fun onToast(message: String) = Unit

                    override fun onSaveRequested(save: PendingSave) = Unit

                    override fun onImportRequested() = Unit

                    override fun onLogsRequested() = Unit
                },
        )
    }
}
