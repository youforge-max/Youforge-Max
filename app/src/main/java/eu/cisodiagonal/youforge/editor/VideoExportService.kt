package eu.cisodiagonal.youforge.editor

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
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import java.io.File

/**
 * Foreground service that renders an [EditorProject] to MP4 via [EditorExporter], so a long
 * export keeps running (YouCut-style) while the app is backgrounded instead of dying with the
 * Activity. Progress is mirrored to [ExportJobs] (for the in-editor UI) and to an ongoing
 * notification with a Cancel action.
 *
 * The project is handed off through [ExportJobs.pending] (not Parcelable; single active export).
 * [EditorExporter] runs the Media3 Transformer on the main looper — fine from a service, whose
 * onStartCommand is already on the main thread.
 */
@OptIn(UnstableApi::class)
class VideoExportService : Service() {

    private var exporter: EditorExporter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            exporter?.cancel()
            ExportJobs.finished(false, "Export cancelled.")
            stopForegroundCompat(); stopSelf(startId)
            return START_NOT_STICKY
        }

        val project = ExportJobs.pending
        if (project == null || project.isEmpty) {
            ExportJobs.finished(false, "No clips to export.")
            stopForegroundCompat(); stopSelf(startId)
            return START_NOT_STICKY
        }
        ExportJobs.pending = null

        ExportJobs.starting()
        startForegroundNotif("Exporting…", -1)

        val ex = EditorExporter(applicationContext)
        exporter = ex
        ex.export(project, object : EditorExporter.Callback {
            override fun onProgress(percent: Int) {
                ExportJobs.progress(percent)
                if (percent < 0) updateNotif("Exporting…", -1)
                else updateNotif("Exporting… $percent%", percent)
            }
            override fun onDone(output: File) {
                ExportJobs.finished(true, "Saved: ${output.name}", output.name)
                stopForegroundCompat(); stopSelf(startId)
            }
            override fun onError(message: String) {
                ExportJobs.finished(false, "Error: $message")
                stopForegroundCompat(); stopSelf(startId)
            }
        })
        // Re-deliver would restart with an empty pending handoff, so don't auto-restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exporter?.cancel()
        exporter = null
        super.onDestroy()
    }

    // ---- notification ----

    private fun startForegroundNotif(text: String, percent: Int) {
        ensureChannel()
        val n = buildNotif(text, percent)
        when {
            Build.VERSION.SDK_INT >= 34 ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String, percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text, percent))
    }

    private fun buildNotif(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting video")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent.coerceIn(0, 100), /* indeterminate= */ percent < 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Video exports", NotificationManager.IMPORTANCE_LOW)
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
        private const val CHANNEL_ID = "yf_video_exports"
        private const val NOTIF_ID = 4012
        private const val ACTION_CANCEL = "eu.cisodiagonal.youforge.EXPORT_CANCEL"

        /** Park [project] and start the export in the foreground service. */
        fun start(context: Context, project: EditorProject) {
            ExportJobs.pending = project
            context.startForegroundService(Intent(context, VideoExportService::class.java))
        }

        /** Cancel a running export (also reachable from the notification action). */
        fun cancel(context: Context) {
            context.startService(
                Intent(context, VideoExportService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
