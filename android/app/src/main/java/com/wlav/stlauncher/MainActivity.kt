package com.wlav.stlauncher

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val NOTIFICATION_PERMISSION_CODE = 1001
    }

    private lateinit var webView: WebView
    @Volatile private var webViewLoaded = false
    @Volatile private var serverPort = 0
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveFileLauncher: ActivityResultLauncher<Intent>
    @Volatile private var pendingSave: PendingSave? = null

    private sealed class PendingSave {
        abstract val fileName: String
        abstract val mimeType: String

        data class Bytes(
            override val fileName: String,
            override val mimeType: String,
            val bytes: ByteArray,
        ) : PendingSave()

        data class PreparedExport(
            override val fileName: String,
            override val mimeType: String,
            val jobId: String,
        ) : PendingSave()
    }

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
            val save = pendingSave
            pendingSave = null
            val uri = result.data?.data
            if (result.resultCode != RESULT_OK || uri == null || save == null) {
                Log.i(TAG, "SAF save cancelled")
                return@registerForActivityResult
            }

            when (save) {
                is PendingSave.Bytes -> writeBytesToUri(uri, save)
                is PendingSave.PreparedExport -> streamPreparedExportToUri(uri, save)
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
        ContextCompat.registerReceiver(
            this,
            portReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Start the Node.js foreground service
        val serviceIntent = Intent(this, NodeService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "正在启动 Node.js...", Toast.LENGTH_SHORT).show()

        // Polling fallback: try to connect to the server in case broadcast is missed
        startServerPolling()

        // 引导用户关闭电池优化，防止系统在后台杀死进程
        requestBatteryOptimizationExemption()
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
        serverPort = port
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

                        // Intercept blob downloads. Keep intercepted URLs alive
                        // until the asynchronous FileReader operation finishes.
                        if (!window._stClickPatchActive) {
                            window._stClickPatchActive = true;
                            var origClick = HTMLAnchorElement.prototype.click;
                            var origRevokeObjectURL = URL.revokeObjectURL.bind(URL);
                            var activeBlobUrls = new Set();
                            var deferredRevokes = new Set();

                            URL.revokeObjectURL = function(url) {
                                if (activeBlobUrls.has(url)) {
                                    deferredRevokes.add(url);
                                    return;
                                }
                                origRevokeObjectURL(url);
                            };

                            function finishBlobDownload(url) {
                                activeBlobUrls.delete(url);
                                if (deferredRevokes.delete(url)) {
                                    origRevokeObjectURL(url);
                                }
                            }

                            function reportBlobFailure(url, error) {
                                console.error('Blob download failed:', error);
                                finishBlobDownload(url);
                                if (window.Android && window.Android.showToast) {
                                    window.Android.showToast('文件处理失败: ' + error);
                                }
                            }

                            HTMLAnchorElement.prototype.click = function() {
                                if (this.hasAttribute('download') && this.href && this.href.startsWith('blob:') && window.Android) {
                                    var fileName = this.getAttribute('download') || 'download';
                                    var url = this.href;
                                    console.log('[STLauncher] Intercepted blob download:', fileName);
                                    activeBlobUrls.add(url);
                                    fetch(url).then(function(r) { return r.blob(); }).then(function(blob) {
                                        var reader = new FileReader();
                                        reader.onload = function() {
                                            try {
                                                var result = String(reader.result || '');
                                                var base64 = result.split(',')[1];
                                                if (!base64) throw new Error('FileReader returned no data');
                                                var mime = blob.type || 'application/octet-stream';
                                                window.Android.saveBase64File(base64, fileName, mime);
                                                finishBlobDownload(url);
                                            } catch (error) {
                                                reportBlobFailure(url, error);
                                            }
                                        };
                                        reader.onerror = function() {
                                            reportBlobFailure(url, reader.error || 'FileReader error');
                                        };
                                        reader.onabort = function() {
                                            reportBlobFailure(url, 'FileReader aborted');
                                        };
                                        reader.readAsDataURL(blob);
                                    }).catch(function(error) { reportBlobFailure(url, error); });
                                    return;
                                }
                                return origClick.call(this);
                            };
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

                // 不使用 createIntent()，因为 Android SAF 无法正确映射
                // .jsonl, .charx, .byaf, .lorebook 等非标准扩展名的 MIME type，
                // 导致用户在文件选择器中看不到这些文件。
                // 统一使用 */* 放开过滤，由前端 JS 校验文件类型。
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
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
                    val safeUrl = JSONObject.quote(url)
                    val safeName = JSONObject.quote(fileName)
                    val safeMime = JSONObject.quote(mimeType ?: "application/octet-stream")
                    val js = "(async function(){" +
                        "try{" +
                        "var r=await fetch($safeUrl);" +
                        "var b=await r.blob();" +
                        "var reader=new FileReader();" +
                        "reader.onload=function(){" +
                        "var base64=reader.result.split(',')[1];" +
                        "window.Android.saveBase64File(base64,$safeName,$safeMime);" +
                        "};" +
                        "reader.onerror=function(){console.error('Blob FileReader error:',reader.error);window.Android.showToast('文件处理失败');};" +
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
        launchDocumentSave(PendingSave.Bytes(fileName, mimeType, bytes))
    }

    private fun launchPreparedExportSave(fileName: String, mimeType: String, jobId: String) {
        launchDocumentSave(PendingSave.PreparedExport(fileName, mimeType, jobId))
    }

    private fun launchDocumentSave(save: PendingSave): Boolean {
        if (pendingSave != null) {
            Toast.makeText(this, "已有保存窗口正在等待处理", Toast.LENGTH_SHORT).show()
            return false
        }

        pendingSave = save
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = save.mimeType
            putExtra(Intent.EXTRA_TITLE, save.fileName)
        }

        return try {
            Log.i(TAG, "Launching SAF save for ${save.fileName}")
            saveFileLauncher.launch(intent)
            true
        } catch (e: Exception) {
            pendingSave = null
            Log.e(TAG, "SAF picker launch failed", e)
            Toast.makeText(this, "无法打开保存窗口: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun writeBytesToUri(uri: Uri, save: PendingSave.Bytes) {
        Thread {
            try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开目标文件")
                output.use { it.write(save.bytes) }
                runOnUiThread {
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "SAF save failed", e)
                deleteIncompleteDocument(uri)
                runOnUiThread {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun streamPreparedExportToUri(uri: Uri, save: PendingSave.PreparedExport) {
        val port = serverPort
        if (port <= 0) {
            deleteIncompleteDocument(uri)
            Toast.makeText(this, "服务器尚未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在写入备份文件...", Toast.LENGTH_SHORT).show()
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = "http://127.0.0.1:$port"
                val downloadUrl = "$baseUrl/api/android/export-data/${save.jobId}"
                connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30_000
                connection.readTimeout = 30 * 60_000
                CookieManager.getInstance().getCookie(baseUrl)?.let {
                    connection.setRequestProperty("Cookie", it)
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: connection.responseMessage
                    throw IOException("服务器返回 $responseCode: $message")
                }

                Log.i(TAG, "Streaming prepared export ${save.jobId} (${connection.contentLengthLong} bytes)")
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开目标文件")
                connection.inputStream.use { input ->
                    output.use { destination ->
                        input.copyTo(destination, 64 * 1024)
                    }
                }

                Log.i(TAG, "Prepared export saved: ${save.fileName}")
                runOnUiThread {
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prepared export save failed", e)
                deleteIncompleteDocument(uri)
                runOnUiThread {
                    Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun deleteIncompleteDocument(uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } else {
                contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove incomplete document: $uri", e)
        }
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
        fun savePreparedExport(jobId: String, fileName: String, mimeType: String): Boolean {
            if (!Regex("""^[0-9a-fA-F-]{36}$""").matches(jobId) || serverPort <= 0) {
                Log.e(TAG, "Rejected invalid prepared export request")
                return false
            }

            runOnUiThread {
                launchPreparedExportSave(
                    fileName.ifBlank { "sillytavern_backup.tar.gz" },
                    mimeType.ifBlank { "application/gzip" },
                    jobId,
                )
            }
            return true
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

    override fun onResume() {
        super.onResume()
        // 确保从后台恢复时 WebView JS 定时器正常运行
        if (::webView.isInitialized) {
            webView.resumeTimers()
        }
    }

    // 注意：故意不 override onPause/onStop 来调用 webView.onPause()/pauseTimers()
    // 这样 WebView 的 JS 引擎在后台仍尝试继续运行

    /**
     * 引导用户关闭电池优化，防止系统在后台杀死进程
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("后台防断连设置")
                .setMessage("因部分操作系统的严格杀后台限制，为防止切换应用时 AI 消息断连：\n\n请点击【去设置】，在应用详情中手动寻找并开启【允许后台运行 / 无限制省电】以及【自启动】权限。")
                .setCancelable(false)
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(fallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "Settings intent failed", e)
                    }
                }
                .setNegativeButton("已设置 / 稍后", null)
                .show()
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
