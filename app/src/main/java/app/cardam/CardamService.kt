package app.cardam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.cardam.data.ShopRepository
import app.cardam.data.TelltaleBook
import app.cardam.navi.NaviAgent
import app.cardam.vehicle.VehicleMonitor
import app.cardam.vehicle.VehicleState
import app.cardam.voice.VoiceAgent
import app.cardam.warning.DemoSettings
import app.cardam.warning.EventStore
import app.cardam.warning.WarningEngine

/**
 * Foreground service that keeps the warning rules running while the app is off screen. Without it the system
 * freezes a background app after about 23 seconds (measured on the Pleos emulator).
 */
class CardamService : Service() {
    lateinit var state: VehicleState
        private set
    lateinit var store: EventStore
        private set
    lateinit var voice: VoiceAgent
        private set
    lateinit var navi: NaviAgent
        private set
    lateinit var monitor: VehicleMonitor
        private set
    lateinit var engine: WarningEngine
        private set

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        StartPrompt.dismiss(this)
        state = VehicleState()
        store = EventStore(this)
        voice = VoiceAgent(this)
        navi = NaviAgent(this)
        engine = WarningEngine(this, state, voice, navi, store, TelltaleBook(this), ShopRepository(this), DemoSettings(this), ::updateStatus)
        monitor = VehicleMonitor(this, state, engine)
        voice.start()
        navi.start()
        engine.start()
        monitor.start()
        instance = this
        log("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again after the car slept: say so once, as nothing on screen shows it while driving.
        when (intent?.getStringExtra(StartPrompt.EXTRA_STARTED_BY)) {
            StartPrompt.BY_PHONE -> voice.say(getString(R.string.started_by_phone))
            StartPrompt.BY_PROMPT -> voice.say(getString(R.string.started_by_prompt))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        engine.stop()
        monitor.stop()
        navi.release()
        voice.release()
        log("service destroyed")
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.app_name)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /**
     * Puts the status in the notification title ("카담 · 타이어 공기압 경고등 켜짐"). The Pleos notification center draws
     * an app's body text very faintly, so the title carries what matters. No pop-up: it is seen only when the
     * notification center is opened.
     */
    private fun updateStatus(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("${getString(R.string.app_name)} · $status"))
    }

    private fun buildNotification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(CHANNEL)
            .build()
    }

    companion object {
        private const val CHANNEL = "warning"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: CardamService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CardamService::class.java))
        }
    }
}
