package es.cnp.ti.movil

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var linkedUri: Uri? = null

    companion object {
        private const val REQ_FILE_CHOOSER = 1
        private const val REQ_OPEN_DRIVE   = 2
        private const val PREFS_NAME       = "TiMovilPrefs"
        private const val PREF_URI         = "driveUri"
    }

    // ── Puente nativo AndroidDrive expuesto al WebView ─────────────────
    inner class AndroidDriveBridge {

        // Buffer para escritura por chunks (evita límite de string del bridge JS→Java)
        private val writeBuffer = StringBuilder()

        @JavascriptInterface
        fun hasFile(): Boolean = linkedUri != null

        @JavascriptInterface
        fun fileName(): String {
            val uri = linkedUri ?: return ""
            return try {
                contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else "" } ?: ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun pick() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "datos_ti.json")
                }
                startActivityForResult(intent, REQ_OPEN_DRIVE)
            }
        }

        @JavascriptInterface
        fun read(): String {
            val uri = linkedUri ?: return "!ERR: No hay archivo vinculado"
            return try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: "!ERR: No se pudo abrir el archivo"
            } catch (e: Exception) {
                "!ERR: ${e.message}"
            }
        }

        // ── Escritura atómica por chunks ───────────────────────────────
        // JS llama: writeStart() → writeChunk(chunk)×N → writeEnd()
        // Así el JSON nunca se trunca aunque supere el límite del bridge.

        @JavascriptInterface
        fun writeStart(): String {
            writeBuffer.clear()
            return "OK"
        }

        @JavascriptInterface
        fun writeChunk(chunk: String): String {
            return try {
                writeBuffer.append(chunk)
                "OK"
            } catch (e: Exception) {
                writeBuffer.clear()
                "!ERR: ${e.message}"
            }
        }

        @JavascriptInterface
        fun writeEnd(): String {
            val uri = linkedUri ?: run { writeBuffer.clear(); return "!ERR: No hay archivo vinculado" }
            return try {
                val content = writeBuffer.toString()
                writeBuffer.clear()
                // Escribir en un fichero temporal primero para no truncar el original si falla
                val tmp = java.io.File(cacheDir, "ti_save_tmp.json")
                tmp.writeText(content, Charsets.UTF_8)
                // Ahora copiar al URI de Drive (modo "wt" = truncar + escribir)
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                } ?: run { tmp.delete(); return "!ERR: No se pudo abrir para escritura" }
                tmp.delete()
                "OK"
            } catch (e: Exception) {
                writeBuffer.clear()
                "!ERR: ${e.message}"
            }
        }

        // write() de compatibilidad — mantener por si acaso
        @JavascriptInterface
        fun write(content: String): String {
            val start = writeStart()
            if (start != "OK") return start
            val chunk = writeChunk(content)
            if (chunk != "OK") return chunk
            return writeEnd()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Restaurar URI guardada de sesiones anteriores
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.getString(PREF_URI, null)?.let { uriStr ->
            val uri = Uri.parse(uriStr)
            // Comprobar que el permiso persiste
            val persisted = contentResolver.persistedUriPermissions
            if (persisted.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
                linkedUri = uri
            }
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Registrar el puente Drive
        webView.addJavascriptInterface(AndroidDriveBridge(), "AndroidDrive")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("tel:")) {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setCancelable(false)
                        .show()
                }
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                runOnUiThread {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("Sí") { _, _ -> result.confirm() }
                        .setNegativeButton("No") { _, _ -> result.cancel() }
                        .setCancelable(false)
                        .show()
                }
                return true
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/json"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Selecciona datos_ti.json"), REQ_FILE_CHOOSER)
                return true
            }
        }

        webView.loadUrl("file:///android_asset/ti_movil_v4.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                val results = if (resultCode == Activity.RESULT_OK && data?.data != null)
                    arrayOf(data.data!!) else null
                fileChooserCallback?.onReceiveValue(results)
                fileChooserCallback = null
            }
            REQ_OPEN_DRIVE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val uri = data.data!!
                    // Solicitar permiso persistente de lectura y escritura
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    linkedUri = uri
                    // Guardar para próximas sesiones
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_URI, uri.toString())
                        .apply()
                    webView.evaluateJavascript("window.onDriveFilePicked(true)", null)
                } else {
                    webView.evaluateJavascript("window.onDriveFilePicked(false)", null)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
