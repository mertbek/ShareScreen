package com.mertbek.sharescreen.android.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.mertbek.sharescreen.android.MainActivity
import com.mertbek.sharescreen.android.R
import com.mertbek.sharescreen.android.ShareScreenApp
import com.mertbek.sharescreen.control.ControlRole
import com.mertbek.sharescreen.session.HostState
import com.mertbek.sharescreen.session.PendingViewer
import com.mertbek.sharescreen.session.ViewerInfo
import com.mertbek.sharescreen.settings.VideoQuality
import com.mertbek.sharescreen.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    private val app get() = application as ShareScreenApp
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shownRequests = mutableSetOf<Int>()
    private var shownController: String? = null
    private var isCapturing = false
    private var isStopping = false
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val requestOverlay by lazy { RequestOverlay(this, app.services) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopSharing()

        override fun onCapturedContentResize(width: Int, height: Int) = app.capture.onContentResized(width, height)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = app.services.host
        when (intent?.action) {
            ACTION_START -> startSharing(intent)
            ACTION_STOP -> stopSharing()
            ACTION_APPROVE -> intent.getStringExtra(EXTRA_VIEWER_ID)?.let(host::approve)
            ACTION_REJECT -> intent.getStringExtra(EXTRA_VIEWER_ID)?.let(host::reject)
            ACTION_GRANT_CONTROL -> intent.getStringExtra(EXTRA_VIEWER_ID)?.let(host::grantControl)
            ACTION_DENY_CONTROL -> intent.getStringExtra(EXTRA_VIEWER_ID)?.let(host::denyControl)
            ACTION_REVOKE_CONTROL -> host.revokeControl()
        }
        if (intent?.action != ACTION_START && !isCapturing && !isStopping) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        requestOverlay.hide()
        cancelRequestNotifications()
        app.wifiLock.release()
        if (!isStopping && isCapturing) app.services.hosting.stop()
        super.onDestroy()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startSharing(intent: Intent) {
        if (isCapturing) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            app.screenSource.startFailed()
            stopSelf()
            return
        }

        val quality = intent.getStringExtra(EXTRA_QUALITY)
            ?.let { name -> VideoQuality.entries.find { it.name == name } }
            ?: VideoQuality.STANDARD
        val shareAudio = intent.getBooleanExtra(EXTRA_SHARE_AUDIO, true) && app.screenAudioInput.hasPermission()
        var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (shareAudio) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, SHARING_NOTIFICATION_ID, buildSharingNotification(), serviceTypes)

        try {
            val projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, resultData)
                ?: error("MediaProjectionManager returned no projection")
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            app.screenSource.startCapture(projection, quality, shareAudio)
            isCapturing = true
            app.wifiLock.acquire()
            observeSession()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start screen capture", e)
            app.screenSource.startFailed()
            finish()
        }
    }

    private fun stopSharing() {
        if (isStopping) return
        isStopping = true
        app.services.hosting.stop()
    }

    private fun finish() {
        isStopping = true
        app.wifiLock.release()
        requestOverlay.hide()
        cancelRequestNotifications()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeSession() {
        serviceScope.launch {
            var hadMedia = false
            app.services.hosting.media.collect { media ->
                if (media != null) hadMedia = true else if (hadMedia) finish()
            }
        }
        serviceScope.launch {
            app.services.host.state.collect { state ->
                val live = state as? HostState.Live
                showRequestNotifications(live)
                val controller = live?.controller?.deviceName
                if (controller != shownController) {
                    shownController = controller
                    notificationManager.notify(SHARING_NOTIFICATION_ID, buildSharingNotification(controller))
                }
            }
        }
        serviceScope.launch {
            // Out of the app, the request itself shows over whatever is on screen.
            combine(app.services.host.state, app.inFront) { state, inFront -> !inFront && (state as? HostState.Live)?.hasRequest() == true }
                .distinctUntilChanged()
                .collect { show -> if (show) requestOverlay.show() else requestOverlay.hide() }
        }
    }

    private fun HostState.Live.hasRequest() = pendingViewers.isNotEmpty() || viewers.any { it.control == ControlRole.REQUESTED }

    private fun showRequestNotifications(live: HostState.Live?) {
        val requests = buildMap<Int, () -> Notification> {
            for (viewer in live?.pendingViewers.orEmpty()) {
                put(requestNotificationId(JOIN_REQUEST_NOTIFICATION_BASE, viewer.id)) { joinRequestNotification(viewer) }
            }
            for (viewer in live?.viewers.orEmpty().filter { it.control == ControlRole.REQUESTED }) {
                put(requestNotificationId(CONTROL_REQUEST_NOTIFICATION_BASE, viewer.id)) { controlRequestNotification(viewer) }
            }
        }
        (shownRequests - requests.keys).forEach(notificationManager::cancel)
        requests.filterKeys { it !in shownRequests }.forEach { (id, build) -> notificationManager.notify(id, build()) }
        shownRequests.clear()
        shownRequests += requests.keys
    }

    private fun joinRequestNotification(viewer: PendingViewer) = requestNotification(
        notificationId = requestNotificationId(JOIN_REQUEST_NOTIFICATION_BASE, viewer.id),
        channelId = JOIN_REQUEST_CHANNEL_ID,
        channelName = R.string.notification_channel_join_requests,
        title = R.string.host_join_request_title,
        text = getString(
            if (viewer.viaInternet) R.string.host_join_request_message_internet else R.string.host_join_request_message,
            viewer.deviceName,
        ),
        viewerId = viewer.id,
        allowAction = ACTION_APPROVE,
        denyAction = ACTION_REJECT,
    )

    private fun controlRequestNotification(viewer: ViewerInfo) = requestNotification(
        notificationId = requestNotificationId(CONTROL_REQUEST_NOTIFICATION_BASE, viewer.id),
        channelId = CONTROL_REQUEST_CHANNEL_ID,
        channelName = R.string.notification_channel_control_requests,
        title = R.string.host_control_request_title,
        text = getString(R.string.host_control_request_message, viewer.deviceName),
        viewerId = viewer.id,
        allowAction = ACTION_GRANT_CONTROL,
        denyAction = ACTION_DENY_CONTROL,
    )

    private fun requestNotification(
        notificationId: Int,
        channelId: String,
        @StringRes channelName: Int,
        @StringRes title: Int,
        text: String,
        viewerId: String,
        allowAction: String,
        denyAction: String,
    ): Notification {
        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, getString(channelName), NotificationManager.IMPORTANCE_HIGH)
        )
        fun action(action: String, index: Int) = PendingIntent.getService(
            this,
            notificationId * 2 + index,
            Intent(this, ScreenCaptureService::class.java)
                .setAction(action)
                .putExtra(EXTRA_VIEWER_ID, viewerId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun builder() = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_screen_share)
            .setContentTitle(getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openHostIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Out of the app the request shows over other apps already, so the notification stays quietly in the list.
            .setSilent(requestOverlay.isAllowed && !app.inFront.value)
            .addAction(0, getString(R.string.host_join_request_allow), action(allowAction, 0))
            .addAction(0, getString(R.string.host_join_request_deny), action(denyAction, 1))
        return builder()
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(builder().build())
            .build()
    }

    private fun cancelRequestNotifications() {
        shownRequests.forEach(notificationManager::cancel)
        shownRequests.clear()
    }

    private fun requestNotificationId(base: Int, viewerId: String) = base + (viewerId.hashCode() and 0xFFFF)

    private fun openHostIntent() = PendingIntent.getActivity(
        this,
        0,
        MainActivity.openHostIntent(this),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildSharingNotification(controller: String? = null): Notification {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                SHARING_CHANNEL_ID,
                getString(R.string.notification_channel_sharing),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val stop = PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, SHARING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_screen_share)
            .setContentTitle(
                if (controller != null) getString(R.string.notification_controlled_title, controller)
                else getString(R.string.notification_sharing_title)
            )
            .setContentIntent(openHostIntent())
        if (controller != null) {
            val revoke = PendingIntent.getService(
                this,
                1,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_REVOKE_CONTROL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.host_control_stop), revoke)
        }
        return builder
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val SHARING_CHANNEL_ID = "screen_sharing"
        private const val JOIN_REQUEST_CHANNEL_ID = "join_requests"
        private const val CONTROL_REQUEST_CHANNEL_ID = "control_requests"
        private const val SHARING_NOTIFICATION_ID = 1
        private const val JOIN_REQUEST_NOTIFICATION_BASE = 1_000
        private const val CONTROL_REQUEST_NOTIFICATION_BASE = 100_000
        private const val ACTION_START = "com.mertbek.sharescreen.action.START_SHARING"
        private const val ACTION_STOP = "com.mertbek.sharescreen.action.STOP_SHARING"
        private const val ACTION_APPROVE = "com.mertbek.sharescreen.action.APPROVE_VIEWER"
        private const val ACTION_REJECT = "com.mertbek.sharescreen.action.REJECT_VIEWER"
        private const val ACTION_GRANT_CONTROL = "com.mertbek.sharescreen.action.GRANT_CONTROL"
        private const val ACTION_DENY_CONTROL = "com.mertbek.sharescreen.action.DENY_CONTROL"
        private const val ACTION_REVOKE_CONTROL = "com.mertbek.sharescreen.action.REVOKE_CONTROL"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_VIEWER_ID = "viewer_id"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_SHARE_AUDIO = "share_audio"

        fun startIntent(context: Context, grant: CaptureGrant, quality: VideoQuality, shareAudio: Boolean): Intent =
            Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, grant.resultCode)
                .putExtra(EXTRA_RESULT_DATA, grant.data)
                .putExtra(EXTRA_QUALITY, quality.name)
                .putExtra(EXTRA_SHARE_AUDIO, shareAudio)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
