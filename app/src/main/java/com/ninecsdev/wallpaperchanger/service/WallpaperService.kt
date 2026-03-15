package com.ninecsdev.wallpaperchanger.service

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.RotationTrigger
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.TimerInterval
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
    private var isPaused = false

    /**
     * True while the rotation schedule is overridden to daily due to an active
     * Do Not Disturb or Nothing OS Focus mode. The user's saved settings are
     * not modified; the override is purely in-memory and auto-reverts when the
     * mode is deactivated.
     */
    private var isFocusDndOverride = false

    // SupervisorJob ensures one failing task doesn't kill the whole service scope
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The companion object holds static members and methods for the service.
     * It is responsible of applying the default wallpaper when the service is stopped.
     */
    companion object {
        const val ACTION_STOP_SERVICE = "com.ninecsdev.wallpaperchanger.ACTION_STOP_SERVICE"

        /** In-memory flag that resets on process death (reboots). */
        @Volatile var isAlive = false
            private set

        suspend fun applyDefaultWallpaper(context: Context) {
            val config = WallpaperRepository.getWallpaperConfig()
            val uri = config.defaultWallpaperUri ?: return

            Log.d("WallpaperService", "Applying default wallpaper...")

            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            WallpaperManager.getInstance(context).setBitmap(
                                bitmap,
                                null,
                                true,
                                WallpaperManager.FLAG_LOCK
                            )
                            Log.i("WallpaperService", "Successfully applied default wallpaper.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WallpaperService", "Fallback failed", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        Log.d(tag, "Service Created")
        WallpaperRepository.initialize(this)
        createNotificationChannel()

        systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                    Intent.ACTION_BATTERY_LOW -> {
                        val pm = context.getSystemService(POWER_SERVICE) as PowerManager
                        if (pm.isPowerSaveMode) {
                            pauseEngine()
                        } else {
                            resumeEngine()
                        }
                    }
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                        // When paused (power save), skip live adjustments; resumeEngine() will
                        // re-evaluate DND/Focus state when the engine is restored.
                        if (!isPaused && isAlive && WallpaperRepository.getWallpaperConfig().followFocusMode) {
                            if (isInFocusOrDndMode()) {
                                applyFocusDndOverride()
                            } else {
                                removeFocusDndOverride()
                            }
                        }
                    }
                }
            }
        }
        val systemFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
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
            WallpaperRepository.setStartingUp(true)
            notifyUiStarted()

            val state = WallpaperRepository.getServiceState()

            if (state !is ServiceState.DisabledNoCollection){
                WallpaperRepository.loadMagazine()
                WallpaperRepository.refillDiskBuffer()

                WallpaperRepository.setServiceRunning(true)
                WallpaperRepository.setStartingUp(false)
                notifyUiStarted()

                val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                val config = WallpaperRepository.getWallpaperConfig()

                if (config.rotationTrigger == RotationTrigger.TIMED) {
                    scheduleTimerAlarm(config.timerInterval.milliseconds)
                    updateNotification("Timed rotation: ${config.timerInterval.displayName} — $activeName")
                } else {
                    registerScreenOffReceiver()
                    updateNotification("Cycling: $activeName")
                }

                // Override to daily rotation when DND or Nothing OS Focus mode is active
                if (config.followFocusMode && isInFocusOrDndMode()) {
                    applyFocusDndOverride()
                }
            }else{
                Log.w(tag, "Abort startup: No collection found.")
                WallpaperRepository.setStartingUp(false)
                handleStopCommand()
            }
        }

        return START_STICKY
    }

    private fun handleStopCommand() {
        Log.i(tag, "Stopping service via command.")
        WallpaperRepository.setServiceRunning(false)
        WallpaperRepository.setServicePaused(false)
        isPaused = false
        isFocusDndOverride = false
        notifyUiStopped()

        cancelTimerAlarm()
        screenOffReceiver?.let {
            unregisterReceiver(it)
            screenOffReceiver = null
        }

        serviceScope.launch {
            val config = WallpaperRepository.getWallpaperConfig()
            if (config.revertToDefaultOnStop) {
                applyDefaultWallpaper(applicationContext)
            }
            stopSelf()
        }
    }

    /**
     * Pauses the wallpaper changing by unregistering the ScreenOffReceiver
     * or cancelling the timer alarm depending on the active rotation trigger.
     * The foreground service stays alive so it can auto-resume.
     */
    private fun pauseEngine() {
        if (isPaused) return
        Log.i(tag, "Pausing engine (Power Save ON)")
        isPaused = true
        WallpaperRepository.setServicePaused(true)

        val config = WallpaperRepository.getWallpaperConfig()
        // When the DND/Focus override is active the engine is always timer-driven,
        // regardless of the user's configured rotationTrigger.
        if (isFocusDndOverride || config.rotationTrigger == RotationTrigger.TIMED) {
            cancelTimerAlarm()
        } else {
            screenOffReceiver?.let {
                unregisterReceiver(it)
                screenOffReceiver = null
            }
        }

        serviceScope.launch {
            if (config.revertToDefaultOnStop) {
                applyDefaultWallpaper(applicationContext)
            }
        }

        updateNotification("Paused (Power Save)")
        notifyUiStarted()
    }

    /**
     * Resumes the wallpaper changing by re-registering the ScreenOffReceiver
     * or rescheduling the timer alarm depending on the active rotation trigger.
     * If DND or Focus mode is still active the daily override is re-applied.
     */
    private fun resumeEngine() {
        if (!isPaused) return
        Log.i(tag, "Resuming engine (Power Save OFF)")
        isPaused = false
        WallpaperRepository.setServicePaused(false)

        if (isInFocusOrDndMode() && WallpaperRepository.getWallpaperConfig().followFocusMode) {
            applyFocusDndOverride()
        } else {
            // Clear any stale override flag that may have been set before the pause
            isFocusDndOverride = false
            val config = WallpaperRepository.getWallpaperConfig()
            if (config.rotationTrigger == RotationTrigger.TIMED) {
                scheduleTimerAlarm(config.timerInterval.milliseconds)
                serviceScope.launch {
                    val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                    updateNotification("Timed rotation: ${config.timerInterval.displayName} — $activeName")
                }
            } else {
                registerScreenOffReceiver()
                serviceScope.launch {
                    val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                    updateNotification("Cycling: $activeName")
                }
            }
        }
        notifyUiStarted()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlive = false
        Log.i(tag, "Service Destroyed. Cleaning up.")

        cancelTimerAlarm()
        screenOffReceiver?.let { unregisterReceiver(it) }
        systemEventReceiver?.let { unregisterReceiver(it) }

        WallpaperRepository.clearMagazine()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // Focus / DND helpers

    /**
     * Returns true when the device is in Do Not Disturb or Nothing OS Focus mode.
     * Both modes surface through the NotificationManager interruption filter.
     * No special permissions are required to read this value.
     */
    private fun isInFocusOrDndMode(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val filter = nm.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * Overrides the active rotation schedule to a daily interval while the device
     * is in DND or Focus mode. The user's persisted settings are not modified.
     */
    private fun applyFocusDndOverride() {
        if (isFocusDndOverride) return
        isFocusDndOverride = true
        Log.i(tag, "Focus/DND mode active. Overriding rotation to daily.")

        val config = WallpaperRepository.getWallpaperConfig()
        if (config.rotationTrigger == RotationTrigger.TIMED) {
            cancelTimerAlarm()
        } else {
            screenOffReceiver?.let {
                unregisterReceiver(it)
                screenOffReceiver = null
            }
        }
        scheduleTimerAlarm(TimerInterval.DAILY.milliseconds)

        serviceScope.launch {
            val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
            updateNotification("Daily rotation (Focus/DND) — $activeName")
        }
        notifyUiStarted()
    }

    /**
     * Restores the original rotation schedule when DND or Focus mode ends.
     */
    private fun removeFocusDndOverride() {
        if (!isFocusDndOverride) return
        isFocusDndOverride = false
        Log.i(tag, "Focus/DND mode ended. Restoring original rotation schedule.")

        cancelTimerAlarm()
        val config = WallpaperRepository.getWallpaperConfig()
        if (config.rotationTrigger == RotationTrigger.TIMED) {
            scheduleTimerAlarm(config.timerInterval.milliseconds)
            serviceScope.launch {
                val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                updateNotification("Timed rotation: ${config.timerInterval.displayName} — $activeName")
            }
        } else {
            registerScreenOffReceiver()
            serviceScope.launch {
                val activeName = WallpaperRepository.getActiveCollectionOnce()?.name ?: "ACTIVE"
                updateNotification("Cycling: $activeName")
            }
        }
        notifyUiStarted()
    }

    // AlarmManager helpers for TIMED rotation mode

    private fun getTimerPendingIntent(): PendingIntent {
        val intent = Intent(this, TimerReceiver::class.java).apply {
            action = TimerReceiver.ACTION_TIMER_WALLPAPER
        }
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleTimerAlarm(intervalMs: Long) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = getTimerPendingIntent()
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            intervalMs,
            pendingIntent
        )
        Log.i(tag, "Timer alarm scheduled: interval=${intervalMs}ms")
    }

    private fun cancelTimerAlarm() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getTimerPendingIntent())
        Log.d(tag, "Timer alarm cancelled.")
    }

    // ScreenOffReceiver helpers

    private fun registerScreenOffReceiver() {
        screenOffReceiver = ScreenOffReceiver()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)
    }

    private fun notifyUiStarted() = WallpaperRepository.notifyServiceStateChanged()
    private fun notifyUiStopped() = WallpaperRepository.notifyServiceStateChanged()

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
