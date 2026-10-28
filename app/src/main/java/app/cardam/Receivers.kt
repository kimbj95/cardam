package app.cardam

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after a full boot. A car resumed from Suspend keeps the process, so no event is needed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            log("boot completed, starting service")
            CardamService.start(context)
        }
    }
}

/**
 * Covers a car that came back from Suspend without our process: the driver's phone reconnecting over Bluetooth
 * wakes the app even when it is not running (an exempt broadcast), and [StartPrompt] starts the service or asks to.
 */
class PhoneConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) StartPrompt.onPhoneConnected(context, goAsync())
    }
}

/**
 * Demo and test commands from adb (the receiver requires the DUMP permission, which only the shell holds):
 * `adb shell am broadcast -a app.cardam.DEMO -n app.cardam/.DemoReceiver --es cmd seed:tpms`
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        when (command) {
            // adb cannot send the protected ACL_CONNECTED broadcast, and the emulator has no Bluetooth.
            "bt:connected" -> StartPrompt.onPhoneConnected(context, goAsync())
            // Stands in for the system stopping the app during Suspend.
            "service:stop" -> {
                log("demo command: $command")
                context.stopService(Intent(context, CardamService::class.java))
            }
            else -> {
                val service = CardamService.instance
                if (service == null) {
                    log("demo command ignored, service not running: $command")
                    return
                }
                service.engine.onDemoCommand(command)
            }
        }
    }
}
