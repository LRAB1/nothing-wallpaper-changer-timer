package com.ninecsdev.wallpaperchanger.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service responsible for keeping the [ScreenOffReceiver] alive,
 * coordinating the whole app and creating and managing the notification.
 */
class WallpaperService : Service() {

    private val tag = "WallpaperService"
    private val channelId = "WallpaperServiceChannel"
    private val notificationId = 1

    private var screenOffReceiver: BroadcastReceiver? = null
    private var systemEventReceiver: BroadcastReceiver? = null
    private var nothingFocusModeReceiver: BroadcastReceiver? = null
    // SupervisorJob ensures one failing task doesn't kill the whole service scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_STOP_SERVICE = "com.ninecsdev.wallpaperchanger.ACTION_STOP_SERVICE"

        /** In-memory flag that resets on process death (reboots). */
        @Volatile var isAlive = false
            private set

        /**
         * Nothing OS Focus Mode broadcast actions. Nothing OS (Phone 1 / Phone 2) fires one of
         * these when the user enables or disables Focus Mode via Quick Settings or the
         * Digital Wellbeing settings screen.
         *
         * The `com.nothing.wellbeing` action is used on Nothing OS 1.x/2.x (Phone 1).
         * The `com.nothing.settings` variant is seen on some Nothing OS 2.5+ builds.
         * Both carry a boolean extra [EXTRA_NOTHING_FOCUS_MODE_ENABLED] (true = active).
         */
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED =
            "com.nothing.wellbeing.action.FOCUS_MODE_STATE_CHANGED"
        const val ACTION_NOTHING_FOCUS_MODE_CHANGED_ALT =
            "com.nothing.settings.action.FOCUS_MODE_STATE_CHANGED"
        const val EXTRA_NOTHING_FOCUS_MODE_ENABLED = "enabled"
    }

    private suspend fun applyDefaultWallpaper() {
        val uri = WallpaperRepository.getDefaultWallpaperUri() ?: return

        Log.d(tag, "Applying default wallpaper...")

        withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        WallpaperManager.getInstance(this@WallpaperService).setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_LOCK
                        )
                        Log.i(tag, "Successfully applied default wallpaper.")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Fallback failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        Log.d(tag, "Service Created")
        WallpaperRepository.initialize(this)
        createNotificationChannel()

        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)

        // Listen for Nothing OS Focus Mode state changes so that isDndActive() has an accurate
        // in-memory flag the moment Focus Mode is toggled — before the next screen-off event.
        nothingFocusModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!intent.hasExtra(EXTRA_NOTHING_FOCUS_MODE_ENABLED)) {
                    Log.w(tag, "Nothing OS Focus Mode broadcast missing '$EXTRA_NOTHING_FOCUS_MODE_ENABLED' extra " +
                        "(action=${intent.action}). Nothing OS API may have changed; defaulting to inactive.")
                    return
                }
                val active = intent.getBooleanExtra(EXTRA_NOTHING_FOCUS_MODE_ENABLED, false)
                Log.d(tag, "Nothing OS Focus Mode broadcast received: enabled=$active (action=${intent.action})")
                WallpaperRepository.setNothingFocusModeActive(active)
            }
        }
        val nothingFocusFilter = IntentFilter().apply {
            addAction(ACTION_NOTHING_FOCUS_MODE_CHANGED)
            addAction(ACTION_NOTHING_FOCUS_MODE_CHANGED_ALT)
        }
        registerReceiver(nothingFocusModeReceiver, nothingFocusFilter, RECEIVER_EXPORTED)

        systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pm = context.getSystemService(POWER_SERVICE) as PowerManager

                // This part stopped the app when battery was under 15% but as we now pause the app
                // it is no longer needed. Keep it in case i want to re-implement it
                /*if (intent.action == Intent.ACTION_BATTERY_LOW) {
                    handleStopCommand()
                    return
                }*/

                if (pm.isPowerSaveMode) {
                    pauseEngine()
                } else {
                    resumeEngine()
                }
            }
        }
        val systemFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        registerReceiver(systemEventReceiver, systemFilter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            handleStopCommand()
            return START_NOT_STICKY
        }

        try {
            startForeground(notificationId, createNotification("INITIALIZING..."))
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                e is ForegroundServiceStartNotAllowedException) {
                Log.w(tag, "Cannot start foreground: app not exempted from battery optimization.", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }

        serviceScope.launch {
            WallpaperRepository.markServiceLoading()
            notifyUi()

            val state = WallpaperRepository.getServiceState()

            if (state !is ServiceState.DisabledNoCollection){
                WallpaperRepository.loadMagazine()
                WallpaperRepository.refillDiskBuffer()

                WallpaperRepository.markServiceRunning()
                notifyUi()

                val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                updateNotification("Cycling: $activeName")
            }else{
                Log.w(tag, "Abort startup: No collection found.")
                WallpaperRepository.markServiceStopped()
                handleStopCommand()
            }
        }

        return START_STICKY
    }

    private fun handleStopCommand() {
        Log.i(tag, "Stopping service via command.")
        WallpaperRepository.markServiceStopped()
        notifyUi()

        serviceScope.launch {
            if (WallpaperRepository.shouldRevertToDefault()) {
                applyDefaultWallpaper()
            }
            stopSelf()
        }
    }

    /**
     * Pauses the wallpaper changing by unregistering the ScreenOffReceiver.
     * The foreground service stays alive so it can auto-resume.
     */
    private fun pauseEngine() {
        if (WallpaperRepository.serviceStateFlow.value is ServiceState.Paused) return
        Log.i(tag, "Pausing engine (Power Save ON)")
        WallpaperRepository.markServicePaused()

        screenOffReceiver?.let {
            unregisterReceiver(it)
            screenOffReceiver = null
        }

        serviceScope.launch {
            if (WallpaperRepository.shouldRevertToDefault()) {
                applyDefaultWallpaper()
            }
        }

        updateNotification("Paused (Power Save)")
        notifyUi()
    }

    /**
     * Resumes the wallpaper changing by re-registering the ScreenOffReceiver.
     */
    private fun resumeEngine() {
        if (WallpaperRepository.serviceStateFlow.value !is ServiceState.Paused) return
        Log.i(tag, "Resuming engine (Power Save OFF)")
        WallpaperRepository.markServiceRunning()

        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)

        serviceScope.launch {
            val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
            updateNotification("Cycling: $activeName")
        }
        notifyUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        Log.i(tag, "Service Destroyed. Cleaning up.")

        screenOffReceiver?.let { unregisterReceiver(it) }
        systemEventReceiver?.let { unregisterReceiver(it) }
        nothingFocusModeReceiver?.let { unregisterReceiver(it) }

        WallpaperRepository.clearMagazine()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notifyUi() = WallpaperRepository.notifyServiceStateChanged()

    // Notification management TODO: Move to separate class in the future.

    /**
     * Notification builder.
     */
    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, createNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId, 
            "Wallpaper Service Status", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null
}
