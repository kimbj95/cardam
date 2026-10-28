package app.cardam

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Brings the warning service back when the driver's phone connects and the service is not running (stopped during
 * Suspend). Android 12+ lets a background app start a foreground service only in listed cases, and a Bluetooth
 * broadcast is not one of them unless the system exempts the app (an OEM allowlist, for example). So the service is
 * started directly where that is allowed; otherwise the "start again?" notification is posted (a tap on it may start
 * the service), and since Pleos does not pop notifications up, a short voice line tells the driver about it.
 */
object StartPrompt {
    private const val CHANNEL = "start"
    private const val NOTIFICATION_ID = 2
    private const val ANNOUNCE_LIMIT_MS = 9_000L

    /** Why the service was started, for the line it says once it is up. */
    const val EXTRA_STARTED_BY = "startedBy"
    const val BY_PHONE = "phone"
    const val BY_PROMPT = "prompt"

    /** [pending] keeps the receiver's process alive until the voice line has been said. */
    fun onPhoneConnected(context: Context, pending: BroadcastReceiver.PendingResult?) {
        if (CardamService.instance != null) {
            log("phone connected, service already running")
            pending?.finish()
            return
        }
        try {
            context.startForegroundService(Intent(context, CardamService::class.java).putExtra(EXTRA_STARTED_BY, BY_PHONE))
            log("phone connected, service started")
            pending?.finish()
            return
        } catch (e: ForegroundServiceStartNotAllowedException) {
            log("phone connected, the service may not start from the background, asking the driver")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.start_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val start = PendingIntent.getForegroundService(
            context, 0, Intent(context, CardamService::class.java).putExtra(EXTRA_STARTED_BY, BY_PROMPT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.start_title))
            .setContentText(context.getString(R.string.start_text))
            .setContentIntent(start)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.start_action), start).build())
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        announce(context.applicationContext, pending)
    }

    /** Says the start prompt aloud with its own short-lived voice, as the app's service is not there to speak. */
    private fun announce(context: Context, pending: BroadcastReceiver.PendingResult?) {
        val tts = TextToSpeech(context, Mode.ON_DEVICE)
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                runCatching { tts.release() }
                pending?.finish()
            }
        }
        main.postDelayed(finish, ANNOUNCE_LIMIT_MS)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(object : EventListener {
                    override fun onReady() = Unit
                    override fun onStart() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                    override fun onDone() { main.post(finish) }
                    override fun onStop() { main.post(finish) }
                    override fun onError(errorType: ErrorType) {
                        log("start prompt voice failed: $errorType")
                        main.post(finish)
                    }
                })
                tts.setLocale(Locale.KOREA)
                log("say: ${context.getString(R.string.start_voice)}")
                tts.speak(context.getString(R.string.start_voice))
            }

            override fun onFailure(errorType: ErrorType) {
                log("start prompt voice unavailable: $errorType")
                main.post(finish)
            }
        })
    }

    /** Called when the service starts by any route, so a stale prompt does not stay on screen. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
