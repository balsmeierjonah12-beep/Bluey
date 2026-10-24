package app.greeny.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Greeny as a stand-alone, hands-free AI helper: no computer needed. Say "Hey Greeny" (or Pinky, Greeny …)
 * and talk. Camera and mic are his eyes and ears, and he answers out loud in German.
 */
class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var camera: CameraWatcher
    private lateinit var live: LiveVoice
    private lateinit var store: SessionStore
    private lateinit var wake: WakeWord
    private lateinit var voice: VoiceID
    private lateinit var templates: WakeTemplates
    private lateinit var ownWake: OwnWake
    private lateinit var duo: DuoLink
    private var btCallback: ((Boolean) -> Unit)? = null
    private val animator = FaceAnimator()
    private val caption = mutableStateOf("")
    private val showApp = mutableStateOf(false)
    private val openSettings = mutableStateOf(false)
    private val main = Handler(Looper.getMainLooper())
    private val clearCaption = Runnable { caption.value = "" }
    private var micCallback: ((Boolean) -> Unit)? = null
    private var orientation: OrientationEventListener? = null
    private var visible = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA) && live.state != LiveVoice.State.ASLEEP) camera.start(this)
        micCallback?.invoke(has(Manifest.permission.RECORD_AUDIO))
        micCallback = null
        btCallback?.invoke(duo.missingPermissions().isEmpty())
        btCallback = null
        if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) live.device.refreshPlace()
    }

    private fun geminiVerifier(pcm: ShortArray, done: (Boolean) -> Unit) =
        GeminiWake.check(settings.apiKey, settings.skin, pcm, done)

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Listen for "Hey <name>" only while he sleeps and the app is open. */
    private fun syncWake() {
        if (!showApp.value) ownWake.testMode = false
        val base = visible && voice.enrolling == null && settings.wakeWord && settings.hasKey &&
            has(Manifest.permission.RECORD_AUDIO) && live.state == LiveVoice.State.ASLEEP
        val ownReady = templates.ready(settings.skin.id)
        // "gemini": short bursts of sound are checked by Gemini. "own": compared with your recordings, on the phone only.
        ownWake.verifier = if (settings.wakeMode == "gemini") this::geminiVerifier else null
        val silentMic = settings.wakeMode == "gemini" || (settings.wakeMode == "own" && ownReady)
        when {
            // The phone's own recognizer (it beeps on many phones).
            base && settings.wakeMode == "system" -> { ownWake.stop(); wake.start() }
            // The mic stays open all the time, silently.
            (base && silentMic) || (ownWake.testMode && visible && live.state == LiveVoice.State.ASLEEP) -> { wake.stop(); ownWake.start() }
            else -> {
                wake.stop()
                if (!ownWake.capturing && !ownWake.testMode) ownWake.stop()
            }
        }
    }

    private val wakeCheck = object : Runnable {
        override fun run() {
            syncWake()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        settings = Settings(this)
        camera = CameraWatcher(this)
        store = SessionStore(this)
        voice = VoiceID(VoiceProfiles(this))
        templates = WakeTemplates(this)
        ownWake = OwnWake(templates)
        duo = DuoLink(this)
        live = LiveVoice(this, settings, camera, voice)
        wake = WakeWord(this)
        wire()

        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                camera.setRotation(when (degrees) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                })
            }
        }

        setContent {
            RootView(live, store, settings, camera, voice, templates, ownWake, duo, animator, caption.value, showApp.value, openSettings.value) {
                showApp.value = it
                if (!it) openSettings.value = false
            }
        }

        // Camera and microphone, asked once up front.
        val missing = mutableListOf<String>()
        if (!has(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA)
        if (!has(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO)
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        visible = true
        // The camera only runs while he is awake: asleep, he just snoozes.
        if (has(Manifest.permission.CAMERA) && live.state != LiveVoice.State.ASLEEP) camera.start(this)
        if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) live.device.refreshPlace()
        orientation?.takeIf { it.canDetectOrientation() }?.enable()
        main.removeCallbacks(wakeCheck)
        main.post(wakeCheck)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        main.removeCallbacks(wakeCheck)
        wake.stop()
        camera.stop()
        orientation?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) live.sleep()
    }

    private fun wire() {
        wake.variants = { settings.skin.wakeVariants }
        wake.onWake = { live.wake() }
        wake.languageTag = { settings.lang.tag }
        ownWake.onWake = { live.wake() }
        ownWake.sensitivity = { settings.wakeSensitivity.toDouble() }
        // Duo mode: two phones, two Greenys, talking over Bluetooth.
        live.duo = duo
        duo.requestPermission = { done ->
            val missing = duo.missingPermissions()
            if (missing.isEmpty()) done(true)
            else { btCallback = done; permissions.launch(missing.toTypedArray()) }
        }
        duo.onLine = { from, text, next -> live.duoLine(from, text, next) }
        duo.onStart = { topic, first -> live.duoStarted(topic, first) }
        duo.onStop = { live.endDuo(false) }
        duo.onDisconnected = { live.endDuo(false) }
        live.onDuoPartnerLine = { animator.duoNod() }
        live.requestMic = { done ->
            if (has(Manifest.permission.RECORD_AUDIO)) done(true) else {
                micCallback = done
                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
        live.onNeedKey = { openSettings.value = true; showApp.value = true }
        live.onSessionStart = {
            wake.stop()
            store.start()
            SessionService.start(this, settings.skin.name)
        }
        live.onSessionEnd = { store.end(); SessionService.stop(this) }
        live.onUserTurn = { item, asked -> store.placeholder(item, asked) }
        live.onUserWords = { item, text, asked -> store.heard(item, text, asked) }
        live.onReply = { store.reply(it) }
        live.onReport = { store.report(it) }
        live.onCaption = { text, finished ->
            main.removeCallbacks(clearCaption)
            caption.value = text
            // The bubble stays a while after he's done, longer for longer lines.
            if (finished) main.postDelayed(clearCaption, 4000L + text.length * 60L)
        }
        live.onStateChange = { state ->
            when (state) {
                LiveVoice.State.ASLEEP -> {
                    animator.awake = false; animator.localMood = Mood.RESTING  // eyes shut, little z's
                    camera.stop()
                    main.postDelayed({ syncWake() }, 800)
                }
                LiveVoice.State.WAKING -> {
                    animator.awake = true; animator.localMood = Mood.HAPPY; wake.stop()
                    if (has(Manifest.permission.CAMERA)) camera.start(this)
                }
                LiveVoice.State.LISTENING -> { animator.awake = true; animator.localMood = null }
                LiveVoice.State.ASKING -> { animator.awake = true; animator.localMood = Mood.LISTENING }
                LiveVoice.State.THINKING -> animator.localMood = Mood.THINKING
                LiveVoice.State.SPEAKING -> animator.localMood = Mood.TALKING
            }
        }
        // He always looks at you: the camera drives his eyes (and a smile makes him happy).
        camera.onFace = { face -> if (face != null) animator.receive(face, System.nanoTime() / 1e9) }
        // Something is shown to him or moves in front of the camera: he looks at it and hops.
        camera.onMotion = { strength, gx, gy ->
            if (live.state != LiveVoice.State.ASLEEP) animator.react(strength.toDouble(), gx.toDouble(), gy.toDouble(), System.nanoTime() / 1e9)
        }
        // An object held up in front of the camera: he looks at it (and hops when it is new).
        camera.onObject = { isNew, gx, gy ->
            if (live.state != LiveVoice.State.ASLEEP) {
                val now = System.nanoTime() / 1e9
                if (isNew) { animator.react(0.6, gx, gy, now); live.objectHint() } else animator.lookAt(gx, gy, 0.7, now)
            }
        }
        animator.localTalk = { live.level }
        animator.localMood = Mood.RESTING  // starts asleep: eyes shut, little z's
    }
}

@Composable
fun RootView(
    live: LiveVoice, store: SessionStore, settings: Settings, camera: CameraWatcher, voice: VoiceID,
    templates: WakeTemplates, ownWake: OwnWake, duo: DuoLink, animator: FaceAnimator,
    caption: String, showApp: Boolean, startOnSettings: Boolean, setShowApp: (Boolean) -> Unit,
) {
    BackHandler(showApp) { setShowApp(false) }
    // In duo mode he looks toward the other phone.
    LaunchedEffect(live.duoActive, live.duoSpeaker, duo.peers, duo.side) {
        if (live.duoActive && duo.peers.isNotEmpty()) {
            val index = duo.peers.indexOfFirst { it.name == live.duoSpeaker }.coerceAtLeast(0)
            val (x, y) = duoPosition(index, duo.peers.size, duo.side)
            animator.duoGazeY = y
            animator.duoGaze = x
        } else {
            animator.duoGaze = null
        }
    }
    val wakeVersion = templates.version
    val canHear = settings.wakeWord && settings.hasKey && (settings.wakeMode != "own" || remember(wakeVersion, Palette.skin.id) { templates.ready(Palette.skin.id) })

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Tap: wake him up (or a little boop when he's awake). Double tap: send him back to sleep.
        // Saying "Hey <name>" wakes him too, without touching.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (live.state == LiveVoice.State.ASLEEP) live.wake() else animator.boop() },
                    onDoubleTap = { if (live.state == LiveVoice.State.ASLEEP) live.wake() else live.sleep() },
                )
            },
        ) { FaceView(animator) }

        ModeIndicator(
            live.state,
            if (canHear) "Sag „Hey ${Palette.skin.name}“" else "Tipp mich an",
        )
        DuoOverlay(live, duo)
        CaptionBubble(caption)
        CameraDot(camera)
        SoundButton(live)

        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 62.dp).size(44.dp).clip(CircleShape).clickable { setShowApp(true) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.GridView, "Menü", Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.35f))
            }
        }

        AnimatedVisibility(showApp, enter = fadeIn(), exit = fadeOut()) {
            GreenyAppView(
                store, settings, voice, live.state != LiveVoice.State.ASLEEP, startOnSettings,
                templates, ownWake, duo, live,
            ) { setShowApp(false) }
        }
        // No key yet: he asks for one before anything else.
        AnimatedVisibility(!settings.hasKey, enter = fadeIn(), exit = fadeOut()) { KeyView(settings) }
        // First start: the language comes first. He speaks only this one.
        AnimatedVisibility(!settings.languageChosen, enter = fadeIn(), exit = fadeOut()) { LanguageView(settings) }
    }
}
