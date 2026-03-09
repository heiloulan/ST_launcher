package com.wlav.stlauncher

import android.Manifest
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private lateinit var webView: WebView
    @Volatile private var webViewLoaded = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveFileLauncher: ActivityResultLauncher<Intent>
    private var pendingDownloadBytes: ByteArray? = null
    private var pendingFileName: String = "download"
    private var pendingMimeType: String = "application/octet-stream"

    private val portReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val port = intent?.getIntExtra(NodeService.EXTRA_PORT, 0) ?: 0
            if (port > 0 && !webViewLoaded) {
                Log.i(TAG, "Received port via broadcast: $port")
                loadWebViewUrl(port)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register the file chooser launcher (must be before setupWebView)
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val results = if (result.resultCode == RESULT_OK && data != null) {
                // Handle single or multiple file selection
                if (data.clipData != null) {
                    Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                } else if (data.data != null) {
                    arrayOf(data.data!!)
                } else null
            } else null
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }

        // Register SAF save-file launcher (user picks save location)
        saveFileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                val bytes = pendingDownloadBytes
                pendingDownloadBytes = null
                if (bytes != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "SAF save failed", e)
                        Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                pendingDownloadBytes = null
            }
        }

        webView = findViewById(R.id.webView)
        setupWebView()

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Load the local loading screen while SillyTavern extracts
        webView.loadUrl("file:///android_asset/loading.html")

        // Register receiver for port broadcast (fast path)
        val filter = IntentFilter(NodeService.ACTION_PORT_READY)
        registerReceiver(portReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Start the Node.js foreground service
        val serviceIntent = Intent(this, NodeService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "正在启动 Node.js...", Toast.LENGTH_SHORT).show()

        // Polling fallback: try to connect to the server in case broadcast is missed
        startServerPolling()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun loadWebViewUrl(port: Int) {
        webViewLoaded = true
        runOnUiThread {
            Log.i(TAG, "Loading WebView: http://127.0.0.1:$port")
            // Clear history so back button won't go back to loading.html
            webView.clearHistory()
            webView.loadUrl("http://127.0.0.1:$port")
        }
    }

    private fun startServerPolling() {
        Thread {
            // Poll ports from 1024..65535 — but we know NodeService writes the port
            // We'll read it from a shared file instead
            val portFile = java.io.File(filesDir, "node_port")
            for (attempt in 1..60) { // try up to 30 seconds
                if (webViewLoaded) return@Thread
                Thread.sleep(500)

                // Check if NodeService wrote the port to a file
                if (portFile.exists()) {
                    try {
                        val port = portFile.readText().trim().toInt()
                        if (port > 0) {
                            // Verify server is actually responding
                            val conn = URL("http://127.0.0.1:$port").openConnection() as HttpURLConnection
                            conn.connectTimeout = 1000
                            conn.readTimeout = 1000
                            val code = conn.responseCode
                            conn.disconnect()
                            if (code == 200 && !webViewLoaded) {
                                Log.i(TAG, "Server ready (poll attempt $attempt), port=$port")
                                loadWebViewUrl(port)
                                return@Thread
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Poll attempt $attempt: ${e.message}")
                    }
                }
            }
            if (!webViewLoaded) {
                Log.e(TAG, "Server did not start within 30 seconds")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(portReceiver)
        } catch (_: Exception) {}
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android) STLauncher/1.0"
        }

        // Register the JavaScript bridge for theme-color updates
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                Log.e(TAG, "WebView error: ${error?.description}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject a MutationObserver to watch for theme-color changes
                view?.evaluateJavascript("""
                    (function() {
                        if (window._stThemeObserverActive) return;
                        window._stThemeObserverActive = true;

                        function sendColor() {
                            var meta = document.querySelector('meta[name="theme-color"]');
                            var color = meta ? meta.getAttribute('content') : null;
                            if (!color && document.body) {
                                color = getComputedStyle(document.body).backgroundColor;
                            }
                            if (color && window.Android) {
                                window.Android.setThemeColor(color);
                            }
                        }

                        // Observe meta tag changes
                        var observer = new MutationObserver(function() { sendColor(); });
                        observer.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });

                        // Also observe body style changes
                        if (document.body) {
                            var bodyObserver = new MutationObserver(function() { sendColor(); });
                            bodyObserver.observe(document.body, { attributes: true, attributeFilter: ['style', 'class'] });
                        }

                        // Initial send + periodic fallback
                        sendColor();
                        setInterval(sendColor, 3000);
                        
                        // Blob download click interceptor
                        if (!window._stBlobInterceptorActive) {
                            window._stBlobInterceptorActive = true;
                            document.addEventListener('click', function(e) {
                                var target = e.target;
                                while (target && target.tagName !== 'A') {
                                    target = target.parentElement;
                                }
                                if (target && target.tagName === 'A' && target.hasAttribute('download') && target.href.startsWith('blob:')) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    var fileName = target.getAttribute('download') || 'download';
                                    var url = target.href;
                                    console.log('Intercepted blob download for:', fileName);
                                    if (window.Android && window.Android.showToast) {
                                        window.Android.showToast("正在处理: " + fileName);
                                    }
                                    fetch(url).then(r => r.blob()).then(blob => {
                                        var reader = new FileReader();
                                        reader.onloadend = function() {
                                            var base64 = reader.result.split(',')[1];
                                            var mime = blob.type || 'application/octet-stream';
                                            if (window.Android) {
                                                window.Android.saveBase64File(base64, fileName, mime);
                                            }
                                        };
                                        reader.readAsDataURL(blob);
                                    }).catch(err => console.error('Blob fetch failed:', err));
                                }
                            }, true);
                        }
                    })()
                """.trimIndent(), null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "WebView console: ${msg?.message()}")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing callback
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "File chooser launch failed", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }

        // File download support — all downloads go through SAF (user picks save location)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    .let { if (it == "downloadfile" || it == "downloadfile.bin") "export_${System.currentTimeMillis()}.json" else it }
                if (url.startsWith("blob:")) {
                    // blob: URL — inject JS to fetch blob, convert to base64, pass via bridge
                    Toast.makeText(this@MainActivity, "正在处理: $fileName", Toast.LENGTH_SHORT).show()
                    val safeUrl = url.replace("'", "\\'")
                    val safeName = fileName.replace("'", "\\'")
                    val safeMime = (mimeType ?: "application/octet-stream").replace("'", "\\'")
                    val js = "(async function(){" +
                        "try{" +
                        "var r=await fetch('$safeUrl');" +
                        "var b=await r.blob();" +
                        "var reader=new FileReader();" +
                        "reader.onloadend=function(){" +
                        "var base64=reader.result.split(',')[1];" +
                        "window.Android.saveBase64File(base64,'$safeName','$safeMime');" +
                        "};" +
                        "reader.readAsDataURL(b);" +
                        "}catch(e){console.error('Blob download error:',e);}" +
                        "})()"
                    webView.evaluateJavascript(js, null)
                } else if (url.startsWith("data:")) {
                    // data: URI — decode base64 in memory
                    val commaIndex = url.indexOf(',')
                    if (commaIndex < 0) return@setDownloadListener
                    val bytes = Base64.decode(url.substring(commaIndex + 1), Base64.DEFAULT)
                    launchSafSave(fileName, mimeType ?: "application/octet-stream", bytes)
                } else {
                    // HTTP URL — download in background, then prompt SAF
                    Toast.makeText(this@MainActivity, "正在下载: $fileName", Toast.LENGTH_SHORT).show()
                    Thread {
                        try {
                            val conn = URL(url).openConnection() as HttpURLConnection
                            conn.setRequestProperty("User-Agent", userAgent)
                            conn.connectTimeout = 30000
                            conn.readTimeout = 60000
                            val bytes = conn.inputStream.readBytes()
                            conn.disconnect()
                            runOnUiThread {
                                launchSafSave(fileName, mimeType ?: "application/octet-stream", bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Download failed", e)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                Toast.makeText(this@MainActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchSafSave(fileName: String, mimeType: String, bytes: ByteArray) {
        pendingDownloadBytes = bytes
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        saveFileLauncher.launch(intent)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun saveBase64File(base64: String, fileName: String, mimeType: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                runOnUiThread {
                    launchSafSave(fileName, mimeType, bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveBase64File failed", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun setThemeColor(color: String) {
            val parsed = parseColor(color)
            if (parsed != null) {
                runOnUiThread {
                    window.statusBarColor = parsed
                    window.navigationBarColor = parsed
                }
            }
        }

        @JavascriptInterface
        fun getLog(): String {
            val logFile = java.io.File(filesDir, "server.log")
            return if (logFile.exists()) {
                // Return last 200 lines to avoid memory issues
                val lines = logFile.readLines()
                lines.takeLast(200).joinToString("\n")
            } else {
                "日志文件暂未生成,请等待服务启动..."
            }
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            // Check if going back would land on loading.html
            val backList = webView.copyBackForwardList()
            val currentIndex = backList.currentIndex
            if (currentIndex > 0) {
                val prevUrl = backList.getItemAtIndex(currentIndex - 1).url
                if (prevUrl.contains("loading.html")) {
                    // Don't go back to loading page, just ignore
                    return
                }
            }
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun parseColor(colorStr: String): Int? {
        return try {
            when {
                colorStr.startsWith("#") -> Color.parseColor(colorStr)
                colorStr.startsWith("rgb") -> {
                    val regex = Regex("""\d+""")
                    val values = regex.findAll(colorStr).map { it.value.toInt() }.toList()
                    if (values.size >= 3) Color.rgb(values[0], values[1], values[2]) else null
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }
}

