package eu.cisodiagonal.youforge.thumb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads on-device models, so a large (~hundreds-of-MB) model
 * keeps downloading while the app is backgrounded instead of being killed with the UI.
 * Progress is mirrored to [ModelDownloads] (for the in-app dialog) and to an ongoing
 * notification. Started with [START_REDELIVER_INTENT] so a process kill re-delivers the
 * intent and the download resumes from its `.part` (see ModelManager.download Range resume).
 */
class ModelDownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif("Preparing download…", -1f)
        val mgr = ModelManager(applicationContext)
        val action = intent?.getStringExtra(EXTRA_ACTION)
        scope.launch {
            try {
                if (action == ACTION_ALL) {
                    ModelDownloads.starting("all", "all models", indeterminate = true)
                    val res = mgr.downloadAll { name, idx, count, p ->
                        val msg = "[$idx/$count] $name"
                        ModelDownloads.progress(name, p, "$msg…")
                        updateNotif(msg, p)
                    }
                    val msg = res.fold(
                        { if (it == 0) "All models already installed." else "Installed $it model(s)." },
                        { "Some downloads failed: ${it.message}" }
                    )
                    ModelDownloads.finished(res.isSuccess, msg)
                } else {
                    val slug = intent?.getStringExtra(EXTRA_SLUG).orEmpty()
                    val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
                    val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
                    val sha = intent?.getStringExtra(EXTRA_SHA)
                    val format = runCatching { ModelFormat.valueOf(intent?.getStringExtra(EXTRA_FORMAT) ?: "TASK") }
                        .getOrDefault(ModelFormat.TASK)
                    if (slug.isBlank() || url.isBlank()) {
                        ModelDownloads.finished(false, "Bad download request.")
                    } else {
                        ModelDownloads.starting(slug, name, indeterminate = false)
                        val res = mgr.download(slug, url, sha, format) { p ->
                            ModelDownloads.progress(name, p, "Downloading $name…")
                            updateNotif(name, p)
                        }
                        ModelDownloads.finished(
                            res.isSuccess,
                            if (res.isSuccess) "Installed $name — now active."
                            else "Download failed: ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
            } finally {
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // ---- notification ----

    private fun startForegroundNotif(text: String, progress: Float) {
        ensureChannel()
        val n = buildNotif(text, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String, progress: Float) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text, progress))
    }

    private fun buildNotif(text: String, progress: Float): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        when {
            progress < 0f -> b.setProgress(0, 0, true)            // indeterminate
            else -> b.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        }
        return b.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
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
        private const val CHANNEL_ID = "yf_model_downloads"
        private const val NOTIF_ID = 4011
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SLUG = "slug"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA = "sha"
        private const val EXTRA_FORMAT = "format"
        private const val ACTION_ALL = "all"
        private const val ACTION_ONE = "one"

        /** Start downloading one model in the foreground service. */
        fun one(
            context: Context, slug: String, name: String, url: String,
            sha256: String?, format: ModelFormat
        ) {
            val i = Intent(context, ModelDownloadService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_ONE)
                putExtra(EXTRA_SLUG, slug); putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_URL, url); putExtra(EXTRA_SHA, sha256)
                putExtra(EXTRA_FORMAT, format.name)
            }
            context.startForegroundService(i)
        }

        /** Start downloading every missing ungated model. */
        fun all(context: Context) {
            val i = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_ALL)
            context.startForegroundService(i)
        }
    }
}
