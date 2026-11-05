package es.cnp.ti.movil

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var driveService: Drive? = null
    private var linkedFileId: String? = null
    private var linkedFileName: String? = null

    companion object {
        private const val REQ_FILE_CHOOSER = 1
        private const val REQ_SIGN_IN = 2
        private const val REQ_OPEN_FILE = 3
        private const val PREFS_NAME = "TiMovilPrefs"
        private const val PREF_FILE_ID = "driveFileId"
        private const val PREF_FILE_NAME = "driveFileName"
    }

    // ── PUENTE AndroidDrive para el WebView ────────────────────────────
    inner class AndroidDriveBridge {

        @JavascriptInterface
        fun hasFile(): Boolean = linkedFileId != null

        @JavascriptInterface
        fun fileName(): String = linkedFileName ?: ""

        @JavascriptInterface
        fun pick() {
            runOnUiThread {
                val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (account == null) {
                    // Pedir login Google con permisos Drive
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                        .requestEmail()
                        .build()
                    val client = GoogleSignIn.getClient(this@MainActivity, gso)
                    startActivityForResult(client.signInIntent, REQ_SIGN_IN)
                } else {
                    buildDriveService(account)
                    openDriveFilePicker()
                }
            }
        }

        @JavascriptInterface
        fun read(): String {
            val fid = linkedFileId ?: return "!ERR: No hay archivo vinculado"
            val svc = driveService ?: return "!ERR: Sin sesión Drive"
            return try {
                val out = ByteArrayOutputStream()
                svc.files().get(fid).executeMediaAndDownloadTo(out)
                out.toString("UTF-8")
            } catch (e: IOException) {
                "!ERR: ${e.message}"
            }
        }

        @JavascriptInterface
        fun write(content: String): String {
            val fid = linkedFileId ?: return "!ERR: No hay archivo vinculado"
            val svc = driveService ?: return "!ERR: Sin sesión Drive"
            return try {
                val mediaContent = com.google.api.client.http.ByteArrayContent("application/json", content.toByteArray(Charsets.UTF_8))
                svc.files().update(fid, File(), mediaContent).execute()
                "OK"
            } catch (e: IOException) {
                "!ERR: ${e.message}"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pantalla completa sin barra de título
        supportActionBar?.hide()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Restaurar archivo vinculado de sesiones anteriores
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        linkedFileId = prefs.getString(PREF_FILE_ID, null)
        linkedFileName = prefs.getString(PREF_FILE_NAME, null)

        // Intentar restaurar la sesión Google Drive
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) buildDriveService(account)

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

        // Puente nativo Drive
        webView.addJavascriptInterface(AndroidDriveBridge(), "AndroidDrive")

        // Evitar que los enlaces abran el navegador del sistema
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
        }

        // Chrome client: alertas JS + selector de archivo
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

        // Cargar el HTML desde los assets (funciona sin internet)
        webView.loadUrl("file:///android_asset/ti_movil_v4.html")
    }

    private fun buildDriveService(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            JacksonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("TiMovil").build()
    }

    private fun openDriveFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra("android.provider.extra.INITIAL_URI",
                android.provider.MediaStore.Files.getContentUri("external"))
        }
        startActivityForResult(intent, REQ_OPEN_FILE)
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
            REQ_SIGN_IN -> {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(Exception::class.java)
                    buildDriveService(account)
                    openDriveFilePicker()
                } catch (e: Exception) {
                    webView.evaluateJavascript("window.onDriveFilePicked(false)", null)
                    Toast.makeText(this, "Error al iniciar sesión en Google: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_OPEN_FILE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val uri = data.data!!
                    // Resolver el File ID de Drive desde el URI
                    val fileId = resolveFileIdFromUri(uri)
                    if (fileId != null) {
                        linkedFileId = fileId
                        try {
                            linkedFileName = driveService?.files()?.get(fileId)
                                ?.setFields("name")?.execute()?.name ?: "datos_ti.json"
                        } catch (e: Exception) {
                            linkedFileName = "datos_ti.json"
                        }
                        // Persistir para próximas sesiones
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_FILE_ID, fileId)
                            .putString(PREF_FILE_NAME, linkedFileName)
                            .apply()
                        webView.evaluateJavascript("window.onDriveFilePicked(true)", null)
                    } else {
                        // Fallback: leer el archivo local y pasarlo como texto
                        webView.evaluateJavascript("window.onDriveFilePicked(false)", null)
                        Toast.makeText(this, "No se pudo obtener el ID de Drive. Usa 'Cargar JSON' para abrir el archivo local.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    webView.evaluateJavascript("window.onDriveFilePicked(false)", null)
                }
            }
        }
    }

    private fun resolveFileIdFromUri(uri: Uri): String? {
        // Drive URIs tienen el formato: content://com.google.android.apps.docs.storage/document/acc=.../id=FILEID
        val path = uri.path ?: return null
        val match = Regex("id=([^/]+)$").find(path)
        return match?.groupValues?.get(1)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
