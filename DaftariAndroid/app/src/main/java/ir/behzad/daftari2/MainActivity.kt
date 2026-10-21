package ir.behzad.daftari2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingBackupJson: String? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var barcodeTarget: String = "sale"

    private inner class DaftarHesabBridge {
        @JavascriptInterface
        fun saveBackup(json: String) {
            runOnUiThread { beginSaveBackup(json) }
        }

        @JavascriptInterface
        fun restoreBackup() {
            runOnUiThread { beginRestoreBackup() }
        }

        @JavascriptInterface
        fun scanBarcode(target: String) {
            runOnUiThread { beginBarcodeScan(target) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.setSupportMultipleWindows(false)
        webView.addJavascriptInterface(DaftarHesabBridge(), "DaftarHesabBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val isOurApp = request.origin.host == "appassets.androidplatform.net"
                    val onlyCamera = request.resources.isNotEmpty() &&
                        request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                    if (!isOurApp || !onlyCamera) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingWebPermissionRequest = request
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermissionRequest === request) pendingWebPermissionRequest = null
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == "appassets.androidplatform.net") return false
                if (uri.scheme == "https" || uri.scheme == "http" || uri.scheme == "mailto" || uri.scheme == "tel") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: ActivityNotFoundException) {
                        true
                    }
                }
                return true
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun beginSaveBackup(json: String) {
        pendingBackupJson = json
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "daftarhesab-backup-$date.json")
        }
        try {
            startActivityForResult(intent, REQUEST_SAVE_BACKUP)
        } catch (e: Exception) {
            pendingBackupJson = null
            notifyBackupSaved(false, e.message ?: "باز کردن محل ذخیره ممکن نشد")
        }
    }

    private fun beginRestoreBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        try {
            startActivityForResult(intent, REQUEST_RESTORE_BACKUP)
        } catch (e: Exception) {
            notifyBackupSaved(false, e.message ?: "باز کردن فایل ممکن نشد")
        }
    }

    private fun beginBarcodeScan(target: String) {
        barcodeTarget = if (target == "product") "product" else "sale"
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES)
                .setPrompt("بارکد را مقابل دوربین بگیر")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setCaptureActivity(CaptureActivity::class.java)
                .initiateScan()
        } catch (e: Exception) {
            notifyBarcodeScanned(barcodeTarget, "")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val barcodeResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (barcodeResult != null) {
            notifyBarcodeScanned(barcodeTarget, barcodeResult.contents ?: "")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        if (requestCode == REQUEST_SAVE_BACKUP) {
            val json = pendingBackupJson
            pendingBackupJson = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (json == null || uri == null) {
                notifyBackupSaved(false, "ذخیره پشتیبان لغو شد")
            } else {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IOException("باز کردن فایل برای نوشتن ناموفق بود")
                    output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    notifyBackupSaved(true, "فایل پشتیبان در محل انتخاب‌شده ذخیره شد")
                } catch (e: Exception) {
                    notifyBackupSaved(false, e.message ?: "نوشتن فایل پشتیبان ناموفق بود")
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        if (requestCode == REQUEST_RESTORE_BACKUP) {
            val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("باز کردن فایل پشتیبان ناموفق بود")
                    val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val quoted = JSONObject.quote(json)
                    webView.evaluateJavascript("window.onNativeBackupLoaded && window.onNativeBackupLoaded($quoted);", null)
                } catch (e: Exception) {
                    notifyBackupSaved(false, e.message ?: "خواندن فایل پشتیبان ناموفق بود")
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun notifyBackupSaved(success: Boolean, message: String) {
        val quoted = JSONObject.quote(message)
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "window.onNativeBackupSaved && window.onNativeBackupSaved($success, $quoted);",
                    null
                )
            }
        }
    }

    private fun notifyBarcodeScanned(target: String, contents: String) {
        val quotedTarget = JSONObject.quote(target)
        val quotedContents = JSONObject.quote(contents)
        if (::webView.isInitialized) {
            webView.post {
                webView.evaluateJavascript(
                    "window.onNativeBarcodeScanned && window.onNativeBarcodeScanned($quotedTarget, $quotedContents);",
                    null
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            val request = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (request != null) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    request.deny()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("DaftarHesabBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SAVE_BACKUP = 2101
        private const val REQUEST_RESTORE_BACKUP = 2102
        private const val REQUEST_CAMERA_PERMISSION = 2201
    }
}
