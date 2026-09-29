package com.mertbek.sharescreen.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mertbek.sharescreen.android.capture.CaptureGrant
import com.mertbek.sharescreen.app.App
import com.mertbek.sharescreen.app.QrScan
import com.mertbek.sharescreen.link.ConnectLink
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val app get() = application as ShareScreenApp
    private val pendingLink = MutableStateFlow<ConnectLink?>(null)
    private val hostRequested = MutableStateFlow(false)
    private var qrCallback: ((QrScan) -> Unit)? = null
    private var wholeScreenOnly = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val link = ConnectLink.parse(contents)
        qrCallback?.invoke(if (link != null) QrScan.Found(link) else QrScan.Invalid)
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else qrCallback?.invoke(QrScan.CameraDenied)
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val grant = if (result.resultCode == Activity.RESULT_OK && data != null) CaptureGrant(result.resultCode, data) else null
        app.captureRequests.deliver(grant)
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        launchConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        app.ui.activity = this
        app.captureRequests.launcher = ::requestCapture
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val link by pendingLink.collectAsState()
            val showHost by hostRequested.collectAsState()
            App(
                services = app.services,
                incomingLink = link,
                onIncomingLinkHandled = { pendingLink.value = null },
                showHost = showHost,
                onShowHostHandled = { hostRequested.value = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (app.ui.activity === this) {
            app.ui.activity = null
            app.captureRequests.launcher = null
            if (isFinishing) app.captureRequests.deliver(null)
        }
        super.onDestroy()
    }

    fun scanQr(onResult: (QrScan) -> Unit) {
        qrCallback = onResult
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun requestCapture(shareAudio: Boolean, wholeScreenOnly: Boolean) {
        this.wholeScreenOnly = wholeScreenOnly
        val optional = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (shareAudio) add(Manifest.permission.RECORD_AUDIO)
        }
        val missing = optional.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray()) else launchConsent()
    }

    private fun launchConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (wholeScreenOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        consentLauncher.launch(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> pendingLink.value = intent.dataString?.let(ConnectLink::parse)
            ACTION_OPEN_HOST -> hostRequested.value = true
        }
    }

    companion object {
        private const val ACTION_OPEN_HOST = "com.mertbek.sharescreen.action.OPEN_HOST"

        fun openHostIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_HOST)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
