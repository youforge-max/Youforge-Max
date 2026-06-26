package eu.youforgemax.youforge.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the offline 5-band normalize ([MediaProcessor.process]) so a
 * long render keeps going while the app is backgrounded instead of dying with the Activity.
 * Progress is mirrored to [NormalizeJobs] (for the in-screen UI) and to an ongoing
 * notification with a Cancel action.
 *
 * The job is handed off through [NormalizeJobs.pending] (SAF in/out URIs + frozen DspConfig;
 * not Parcelable, single active job). [MediaProcessor.process] is blocking CPU work → run on
 * an IO thread; cancel is cooperative (a flag checked in the progress callback aborts it).
 */
class NormalizeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }

        val req = NormalizeJobs.pending
        if (req == null) {
            NormalizeJobs.finished(false, "Nothing to normalize.")
            stopForegroundCompat(); stopSelf(startId)
            return START_NOT_STICKY
        }
        NormalizeJobs.pending = null
        cancelled = false

        NormalizeJobs.starting()
        startForegroundNotif("Starting…", 0f)

        scope.launch {
            val result = runCatching {
                MediaProcessor.process(applicationContext, req.input, req.output, req.cfg) { phase, frac ->
                    if (cancelled) throw CancellationException("cancelled")
                    NormalizeJobs.progress(phase, frac)
                    updateNotif("$phase  ${(frac * 100).toInt()}%", frac)
                }
            }
            val msg = when {
                result.isSuccess -> "Saved normalized video: ${req.outName}"
                cancelled || result.exceptionOrNull() is CancellationException -> "Normalize cancelled."
                else -> "Error: ${result.exceptionOrNull()?.message}"
            }
            NormalizeJobs.finished(result.isSuccess, msg)
            stopForegroundCompat(); stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        job.cancel()
        super.onDestroy()
    }

    // ---- notification ----

    private fun startForegroundNotif(text: String, frac: Float) {
        ensureChannel()
        val n = buildNotif(text, frac)
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String, frac: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text, frac))
    }

    private fun buildNotif(text: String, frac: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, NormalizeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Normalizing audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, (frac * 100).toInt().coerceIn(0, 100), false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Audio normalize", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "yf_audio_normalize"
        private const val NOTIF_ID = 4013
        private const val ACTION_CANCEL = "eu.youforgemax.youforge.NORMALIZE_CANCEL"

        /** Park the job and start the normalize in the foreground service. */
        fun start(context: Context, input: android.net.Uri, output: android.net.Uri, cfg: DspConfig, outName: String) {
            NormalizeJobs.pending = NormalizeJobs.Request(input, output, cfg, outName)
            context.startForegroundService(Intent(context, NormalizeService::class.java))
        }

        /** Cancel a running normalize (also reachable from the notification action). */
        fun cancel(context: Context) {
            context.startService(
                Intent(context, NormalizeService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
