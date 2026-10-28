package app.cardam.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.cardam.log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitializeListener

/**
 * One voice for the whole app, on the Pleos on-device TTS and STT (no network, no app registration).
 * Utterances are queued so they never overlap; an urgent one goes to the front. A question is followed by a short
 * beep when the microphone is ready, and is asked once more if the answer is not understood.
 */
class VoiceAgent(private val context: Context) {

    private sealed class Job {
        class Say(val text: String) : Job()
        class Ask(
            val question: String,
            val parse: (String) -> Any?,
            val onResult: (Any?, String) -> Unit,
        ) : Job() {
            var retried = false
        }
    }

    private class Listening(val job: Job.Ask, val injected: Boolean) {
        var heard = ""
        var ready = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private var ttsReady = false
    private var sttReady = false
    private val queue = ArrayDeque<Job>()
    private var current: Job? = null
    private var listening: Listening? = null

    /** Raw 16 kHz mono PCM fed to the recognizer instead of the microphone at the next question (demo, tests). */
    var injectedAnswer: ByteArray? = null

    val busy: Boolean get() = current != null || queue.isNotEmpty()

    fun start() {
        tts = TextToSpeech(context, TtsMode.ON_DEVICE)
        tts.initialize(object : TtsInitializeListener {
            override fun onSuccess() {
                tts.addEventListener(ttsListener)
                tts.setLocale(Locale.KOREA)
                main.post {
                    ttsReady = true
                    log("TTS ready")
                    pump()
                }
            }

            override fun onFailure(errorType: TtsErrorType) = log("TTS initialize failed: $errorType")
        })
        stt = SpeechToText(context, SttMode.ON_DEVICE)
        stt.initialize(object : SttInitializeListener {
            override fun onSuccess() {
                stt.addListener(sttListener)
                stt.setLocale(Locale.KOREA)
                main.post {
                    sttReady = true
                    log("STT ready")
                    pump()
                }
            }

            override fun onFailure(errorType: SttErrorType) = log("STT initialize failed: $errorType")
        })
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        if (::tts.isInitialized) {
            tts.removeEventListener(ttsListener)
            tts.release()
        }
        if (::stt.isInitialized) {
            stt.removeListener(sttListener)
            stt.release()
        }
    }

    /** Speaks [text]. An urgent message goes before everything that is still waiting. */
    fun say(text: String, urgent: Boolean = false) {
        if (urgent) queue.addFirst(Job.Say(text)) else queue.addLast(Job.Say(text))
        pump()
    }

    /** Asks a yes/no question and reports the answer (UNKNOWN after one retry). */
    fun askYesNo(question: String, onAnswer: (Answer) -> Unit) =
        ask(question, { Answers.yesNo(it).takeIf { a -> a != Answer.UNKNOWN } }) { result, _ ->
            onAnswer(result as? Answer ?: Answer.UNKNOWN)
        }

    /** Asks a question whose answer [parse] turns into a value; null after one retry means not understood. */
    fun ask(question: String, parse: (String) -> Any?, onResult: (Any?, String) -> Unit) {
        queue.addLast(Job.Ask(question, parse, onResult))
        pump()
    }

    // ---------------------------------------------------------------- queue
    private fun pump() {
        if (current != null || !ttsReady) return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        when (next) {
            is Job.Say -> speak(next.text)
            is Job.Ask -> speak(next.question)
        }
    }

    private fun speak(text: String) {
        log("say: $text")
        tts.speak(text)
    }

    private fun onSpeechFinished(interrupted: Boolean) {
        val job = current ?: return
        if (job is Job.Ask && !interrupted) {
            listen(job)
            return
        }
        current = null
        pump()
    }

    // ---------------------------------------------------------------- listening
    private fun listen(job: Job.Ask) {
        val injected = injectedAnswer
        injectedAnswer = null
        val session = Listening(job, injected != null)
        listening = session
        if (!sttReady) {
            finishListening("stt not ready")
            return
        }
        if (injected != null) {
            log("listen (injected ${injected.size} bytes)")
            session.ready = true
            Thread {
                for (start in injected.indices step BYTES_PER_100_MS) {
                    stt.sendAudio(injected.copyOfRange(start, minOf(start + BYTES_PER_100_MS, injected.size)))
                    Thread.sleep(100L)
                }
                stt.completeAudioSend()
            }.start()
            main.postDelayed(timeout, injected.size / BYTES_PER_MS + LISTEN_MS)
        } else {
            log("listen (microphone)")
            stt.request()
            main.postDelayed(timeout, READY_LIMIT_MS)
        }
    }

    private val timeout = Runnable {
        if (listening?.injected == false) stt.stop()
        finishListening("timeout")
    }

    private fun finishListening(reason: String) {
        val session = listening ?: return
        listening = null
        main.removeCallbacks(timeout)
        val job = session.job
        val parsed = session.heard.takeIf { it.isNotBlank() }?.let(job.parse)
        log("heard '${session.heard}' -> $parsed ($reason)")
        if (parsed == null && !job.retried) {
            job.retried = true
            speak("다시 말씀해 주세요.")
            return
        }
        current = null
        job.onResult(parsed, session.heard)
        pump()
    }

    // ---------------------------------------------------------------- SDK listeners
    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() {
            main.post { onSpeechFinished(interrupted = false) }
        }

        override fun onStop() {
            main.post { onSpeechFinished(interrupted = true) }
        }

        override fun onError(errorType: TtsErrorType) {
            log("TTS error $errorType")
            main.post { onSpeechFinished(interrupted = false) }
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() {
            main.post {
                val session = listening ?: return@post
                if (session.injected || session.ready) return@post
                session.ready = true
                // The listening window starts once the engine is ready; loading can take seconds.
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, LISTEN_MS)
                beep()
            }
        }

        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                val session = listening ?: return@post
                if (stt.isBlank()) return@post
                session.heard = stt
                if (completed) finishListening("final")
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() = Unit
        override fun onError(errorType: SttErrorType) {
            log("STT error $errorType")
            main.post { finishListening("error $errorType") }
        }
    }

    /** Short earcon that tells the driver the microphone is listening now. */
    private fun beep() {
        val rate = 16_000
        val samples = rate * BEEP_MS / 1000
        val fadeSamples = rate / 100
        val pcm = ShortArray(samples) { i ->
            val fade = minOf(1.0, minOf(i, samples - i).toDouble() / fadeSamples)
            (sin(2 * PI * BEEP_HZ * i / rate) * fade * 0.3 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples * 2)
            .build()
        track.write(pcm, 0, samples)
        track.play()
        log("beep at ${SystemClock.elapsedRealtime()}")
        main.postDelayed({ track.release() }, 1_000L)
    }

    private companion object {
        const val BYTES_PER_100_MS = 3_200
        const val BYTES_PER_MS = 32
        const val LISTEN_MS = 8_000L
        const val READY_LIMIT_MS = 20_000L
        const val BEEP_MS = 120
        const val BEEP_HZ = 880.0
    }
}
