/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3.petalos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.util.Log
import com.android.launcher3.Flags
import com.android.launcher3.R
import com.android.launcher3.dagger.LauncherComponentProvider.appComponent
import com.android.launcher3.graphics.theme.ThemePreference
import com.android.launcher3.icons.BaseIconFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user-started batch owner. Foreground lifetime survives picker dismissal. */
class SubjectIconService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cancellation = AtomicBoolean(false)
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == "generate" && isRunning) return START_NOT_STICKY
        cancellation.set(true)
        val token = AtomicBoolean(false)
        cancellation = token
        activeToken = token
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.subject_icons_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(2381, notification(0, 0))
        isRunning = true
        if (action == "clear") SubjectIconStore.disable(this)
        SubjectIconStore.prefs(this).edit().putString("state", "processing")
            .putString("error", "").putInt("done", 0).putInt("total", 0).apply()
        worker.execute {
            try {
                if (action == "clear") {
                    // Clear every model generation, including pre-v3 cutouts left by an upgrade.
                    check(File(noBackupFilesDir, "subject-icons").deleteRecursively()) { "Could not clear icon images" }
                    File(noBackupFilesDir, "subject-model").deleteRecursively()
                    SubjectIconStore.prefs(this).edit().remove("generation").putString("state", "idle")
                        .putInt("done", 0).putInt("total", 0).putInt("fallbacks", 0).commit()
                } else {
                    generate(token)
                }
            } catch (error: Exception) {
                Log.e("SubjectIcons", "Icon batch failed", error)
                if (!token.get()) SubjectIconStore.prefs(this).edit()
                    .putString("state", "error").putString("error", error.message ?: error.javaClass.simpleName).commit()
            } finally {
                main.post {
                    if (cancellation === token) {
                        isRunning = false
                        if (token.get()) SubjectIconStore.prefs(this).edit().putString("state", "cancelled").apply()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // A duplicate Generate request may have delivered a newer startId.
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun generate(token: AtomicBoolean) {
        check(!IconPackManager.get(this).isPackActive) { getString(R.string.subject_icons_default_pack) }
        val launcherApps = getSystemService(LauncherApps::class.java)
        val users = getSystemService(UserManager::class.java).userProfiles
        val activities = users.flatMap { user -> launcherApps.getActivityList(null, user) }
            .sortedBy { it.componentName.flattenToString() }
        val prefs = SubjectIconStore.prefs(this)
        val generation = "g${System.currentTimeMillis()}"
        val directory = File(SubjectIconStore.root(this), generation)
        check(directory.mkdirs()) { "Could not create icon cache" }
        val masks = File(directory, "masks").apply { check(mkdirs()) }
        val previous = SubjectIconStore.current(this)
        // Retain the published generation during refresh, discard interrupted/stale batches.
        SubjectIconStore.root(this).listFiles()?.filter { it != previous && it != directory }
            ?.forEach { it.deleteRecursively() }
        val idp = appComponent.launcherAppState.invariantDeviceProfile
        val dpi = idp.fillResIconDpi
        // A plain factory keeps the batch independent of the currently selected theme.
        val factory = BaseIconFactory(this, dpi, idp.iconBitmapSize,
            Flags.enableLauncherIconShapes())
        var done = 0
        var fallbacks = 0
        var processed = 0
        prefs.edit().putInt("total", activities.size).commit()
        try {
            SubjectIconSegmenter(this).use { segmenter ->
                for (activity in activities) {
                    if (token.get()) return
                    // Use the launcher's own flattened bitmap so the mask hash matches the
                    // BitmapInfo the theme controller later receives, including legacy icons.
                    try {
                        val drawable = appComponent.launcherAppState.iconProvider.getIcon(activity.activityInfo, dpi)
                        val info = factory.createBadgedIconBitmap(drawable)
                        val icon = info.icon
                        try {
                            val hash = SubjectIconStore.hash(icon)
                            val target = SubjectIconStore.image(directory, hash)
                            if (target.isFile) {
                                processed++
                            } else if (previous != null && SubjectIconStore.image(previous, hash).isFile &&
                                File(previous, "masks/$hash.png").isFile) {
                                SubjectIconStore.image(previous, hash).copyTo(target)
                                File(previous, "masks/$hash.png").copyTo(File(masks, "$hash.png"))
                                processed++
                            } else {
                                val input = SubjectIconStore.resize(icon)
                                try {
                                    val output = segmenter.cutout(input)
                                    if (output == null) {
                                        fallbacks++
                                    } else {
                                        try {
                                            SubjectIconStore.write(target, output.first)
                                            SubjectIconStore.write(File(masks, "$hash.png"), output.second)
                                            processed++
                                        } finally {
                                            output.first.recycle()
                                            output.second.recycle()
                                        }
                                    }
                                } finally {
                                    if (input !== icon) input.recycle()
                                }
                            }
                        } finally {
                            icon.recycle()
                        }
                    } catch (error: Exception) {
                        fallbacks++
                        Log.w("SubjectIcons", "Keeping original ${activity.componentName}", error)
                    }
                    done++
                    prefs.edit().putInt("done", done).putInt("fallbacks", fallbacks).apply()
                    if (done % 5 == 0 || done == activities.size) {
                        getSystemService(NotificationManager::class.java).notify(2381, notification(done, activities.size))
                    }
                }
            }
            check(processed > 0) { "No usable icon masks were generated" }
            // Publish on main, after every PNG is durable. A late cancel must never re-enable.
            main.post {
                if (!token.get()) {
                    prefs.edit().putString("generation", generation).putString("state", "ready")
                        .putInt("done", done).putInt("fallbacks", fallbacks).commit()
                    if (!IconPackManager.get(this).isPackActive) SubjectIconStore.enable(this, generation)
                } else {
                    directory.deleteRecursively()
                }
            }
        } finally {
            if (token.get() || processed == 0 || done != activities.size) directory.deleteRecursively()
            // Release ORT arena/session before leaving the service. No persistent ML in Launcher.
            factory.close()
        }
    }

    private fun notification(done: Int, total: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.subject_icons_processing))
        .setContentText("$done / $total")
        .setProgress(total, done, total == 0)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        cancellation.set(true)
        if (activeToken === cancellation) activeToken = null
        worker.shutdown()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "subject_icon_processing"
        @Volatile var isRunning = false
            private set
        @Volatile private var activeToken: AtomicBoolean? = null

        @JvmStatic fun cancelGeneration(context: Context) {
            activeToken?.set(true)
            if (isRunning) SubjectIconStore.prefs(context).edit().putString("state", "cancelled").apply()
            Handler(Looper.getMainLooper()).post {
                context.stopService(Intent(context, SubjectIconService::class.java))
            }
        }

        @JvmStatic fun command(context: Context, action: String) {
            check(action in setOf("generate", "disable", "clear", "enable", "default", "themed"))
            val main = Handler(Looper.getMainLooper())
            main.post {
                when (action) {
                    "default", "themed" -> {
                        // One main-thread transaction. A separate asynchronous disable could
                        // otherwise restore the old mode after the new mode had been applied.
                        cancelGeneration(context)
                        context.appComponent.themePreference.setValue(
                            if (action == "themed") ThemePreference.MONO_THEME_VALUE else null)
                        SubjectIconStore.notify(context)
                    }
                    "disable" -> {
                        cancelGeneration(context)
                        SubjectIconStore.disable(context)
                    }
                    "enable" -> {
                        val generation = SubjectIconStore.prefs(context).getString("generation", null)
                        if (!IconPackManager.get(context).isPackActive && generation != null &&
                            SubjectIconStore.current(context)?.isDirectory == true) {
                            SubjectIconStore.enable(context, generation)
                        } else {
                            SubjectIconStore.prefs(context).edit().putString("state", "error")
                                .putString("error", context.getString(R.string.subject_icons_default_pack)).apply()
                        }
                    }
                    else -> try {
                        context.startForegroundService(Intent(context, SubjectIconService::class.java).setAction(action))
                    } catch (error: RuntimeException) {
                        Log.e("SubjectIcons", "Could not start batch service", error)
                        SubjectIconStore.prefs(context).edit().putString("state", "error")
                            .putString("error", error.message ?: error.javaClass.simpleName).apply()
                    }
                }
            }
        }
    }
}
