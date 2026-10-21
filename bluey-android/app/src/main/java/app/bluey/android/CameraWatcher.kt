package app.bluey.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * His eyes: the front camera, always on while the app is open. It finds your face so he can look at you,
 * and can hand over a fresh photo of you for the AI. Nothing is shown on screen and nothing leaves the phone
 * except photos that are sent along with a question (if that is switched on).
 */
class CameraWatcher(private val context: Context) {
    var running by mutableStateOf(false); private set
    var faceVisible by mutableStateOf(false); private set

    /** Called on the main thread with where your face is (and whether you smile), or null when it's gone. */
    var onFace: ((FaceState?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var snapshotCallback: ((String?) -> Unit)? = null
    private var lastFaceAt = 0L
    @Volatile private var wanted = false
    @Volatile private var lastFaceBox: android.graphics.Rect? = null

    /** An object (not you) is in view: (is it new, where he should look: gazeX, gazeY). Main thread. */
    var onObject: ((Boolean, Double, Double) -> Unit)? = null

    private val objects = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )
    private val seen = HashMap<Int, Long>()
    private var lastObjectAt = 0L

    /** Something moved in front of the camera (strength 0…1, and where he should look: gazeX, gazeY). Main thread. */
    var onMotion: ((Float, Float, Float) -> Unit)? = null

    private var liveInterval = 1000L
    @Volatile private var liveCallback: ((String) -> Unit)? = null
    private var lastLive = 0L
    private var prevGrid: IntArray? = null
    private var lastMotion = 0L

    /** Live video for the AI: a small JPEG (base64) about every [intervalMs] while the camera runs. Called on a background thread. */
    fun startLive(intervalMs: Long, callback: (String) -> Unit) { liveInterval = intervalMs; liveCallback = callback }

    fun stopLive() { liveCallback = null }

    fun start(owner: LifecycleOwner) {
        wanted = true
        if (running) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!wanted) return@addListener
            try {
                val p = future.get()
                provider = p
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                a.setAnalyzer(executor) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                analysis = a
                running = true
            } catch (e: Exception) {
                running = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        wanted = false
        provider?.unbindAll()
        analysis = null
        running = false
        faceVisible = false
    }

    /** Keeps the image upright when the phone is flipped between the two landscape directions. */
    fun setRotation(surfaceRotation: Int) { analysis?.targetRotation = surfaceRotation }

    /** A fresh JPEG (base64) of what the camera sees right now, or null if the camera isn't running. */
    fun snapshot(done: (String?) -> Unit) {
        if (!running) { done(null); return }
        snapshotCallback = done
        main.postDelayed({
            val cb = snapshotCallback
            if (cb === done) { snapshotCallback = null; done(null) }
        }, 1500)
    }

    /** Every frame must be closed, whatever goes wrong, or the camera stream stalls and his eyes freeze. */
    private fun analyze(proxy: ImageProxy) {
        try {
            analyzeFrame(proxy)
        } catch (e: Exception) {
            try { proxy.close() } catch (_: Exception) {}
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(proxy: ImageProxy) {
        snapshotCallback?.let { cb ->
            snapshotCallback = null
            cb(encode(proxy, 768, 70))
        }
        val clock = SystemClock.elapsedRealtime()
        liveCallback?.let { cb ->
            if (clock - lastLive >= liveInterval) {
                lastLive = clock
                encode(proxy, 384, 55)?.let(cb)
            }
        }
        detectMotion(proxy, clock)
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val upright = rotation % 180 == 0
        val w = (if (upright) proxy.width else proxy.height).toFloat()
        val h = (if (upright) proxy.height else proxy.width).toFloat()
        val input = InputImage.fromMediaImage(media, rotation)
        val faceTask = detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    lastFaceBox = null
                    if (System.currentTimeMillis() - lastFaceAt > 1500 && faceVisible) {
                        faceVisible = false
                        onFace?.invoke(null)
                    }
                } else {
                    lastFaceAt = System.currentTimeMillis()
                    lastFaceBox = face.boundingBox
                    val cx = face.boundingBox.exactCenterX() / w
                    val cy = face.boundingBox.exactCenterY() / h
                    // The front camera image isn't mirrored, so flip x: if you're to the right of the phone, he looks right.
                    val gx = ((0.5f - cx) * 2f * 1.6f).coerceIn(-1f, 1f).toDouble()
                    val gy = ((cy - 0.5f) * 2f * 1.4f).coerceIn(-1f, 1f).toDouble()
                    val smiling = (face.smilingProbability ?: 0f) > 0.75f
                    faceVisible = true
                    onFace?.invoke(FaceState(gx, gy, if (smiling) Mood.HAPPY else Mood.LISTENING))
                }
            }
        val tasks = ArrayList<com.google.android.gms.tasks.Task<*>>()
        tasks.add(faceTask)
        if (clock - lastObjectAt >= 200) {
            lastObjectAt = clock
            tasks.add(objects.process(input).addOnSuccessListener { list -> handleObjects(list, w, h) })
        }
        com.google.android.gms.tasks.Tasks.whenAllComplete(tasks).addOnCompleteListener { proxy.close() }
    }

    /** The biggest object that isn't you: he looks at it for a few seconds (and hops when it is new). */
    private fun handleObjects(list: List<DetectedObject>, w: Float, h: Float) {
        if (objectVisible) return   // the picture tracker already follows something
        val face = lastFaceBox
        val candidate = list.filter { o ->
            val b = o.boundingBox
            val area = b.width() * b.height()
            val overlapsFace = face != null && android.graphics.Rect(b).let { r ->
                r.intersect(face) && r.width() * r.height() > 0.5f * area
            }
            area > w * h * 0.015f && !overlapsFace
        }.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
        val now = SystemClock.elapsedRealtime()
        if (seen.size > 40) seen.clear()
        val id = candidate.trackingId ?: -1
        val isNew = !seen.containsKey(id)
        val first = seen.getOrPut(id) { now }
        if (now - first > 5000) return   // stared long enough at this one
        val gx = ((0.5f - candidate.boundingBox.exactCenterX() / w) * 2f * 1.6f).coerceIn(-1f, 1f).toDouble()
        val gy = ((candidate.boundingBox.exactCenterY() / h - 0.5f) * 2f * 1.4f).coerceIn(-1f, 1f).toDouble()
        main.post { onObject?.invoke(isNew, gx, gy) }
    }

    private var bg: FloatArray? = null
    private var objectRun = 0
    private var lastObjectSeen = 0L
    private var lastObjectReport = 0L
    /** True while something that isn't your face is held in front of the camera. */
    @Volatile var objectVisible = false; private set

    /**
     * Cheap analysis on a 40×30 grid of brightness values. It finds (1) big, sudden movement, and (2) anything new in the
     * picture that isn't your face, by comparing with a slowly adapting picture of the room. Then he looks at it.
     */
    private fun detectMotion(proxy: ImageProxy, clock: Long) {
        try {
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val w = proxy.width
            val h = proxy.height
            val rot = proxy.imageInfo.rotationDegrees
            val uw = (if (rot % 180 == 0) w else h).toFloat()
            val uh = (if (rot % 180 == 0) h else w).toFloat()
            val gw = 40
            val gh = 30
            val grid = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val y = min(h - 1, gy * h / gh)
                for (gx in 0 until gw) {
                    val x = min(w - 1, gx * w / gw)
                    grid[gy * gw + gx] = buffer.get(y * rowStride + x).toInt() and 0xFF
                }
            }
            trackForeground(grid, gw, gh, rot, uw, uh, clock)

            val prev = prevGrid
            prevGrid = grid
            if (prev == null) return
            var changed = 0
            var sx = 0.0
            var sy = 0.0
            for (i in grid.indices) {
                if (abs(grid[i] - prev[i]) > 28) { changed += 1; sx += i % gw; sy += i / gw }
            }
            val strength = changed / grid.size.toFloat()
            if (strength < 0.12f || clock - lastMotion < 2000) return
            lastMotion = clock
            val nx = ((sx / changed + 0.5) / gw).toFloat()
            val ny = ((sy / changed + 0.5) / gh).toFloat()
            val ux = when (rot) { 90 -> 1 - ny; 180 -> 1 - nx; 270 -> ny; else -> nx }
            val uy = when (rot) { 90 -> nx; 180 -> 1 - ny; 270 -> 1 - nx; else -> ny }
            val gx = ((0.5f - ux) * 2f * 1.6f).coerceIn(-1f, 1f)
            val gy = ((uy - 0.5f) * 2f * 1.4f).coerceIn(-1f, 1f)
            main.post { onMotion?.invoke(min(1f, strength * 2f), gx, gy) }
        } catch (_: Exception) {
        }
    }

    private fun trackForeground(grid: IntArray, gw: Int, gh: Int, rot: Int, uw: Float, uh: Float, clock: Long) {
        val back = bg
        if (back == null || back.size != grid.size) { bg = FloatArray(grid.size) { grid[it].toFloat() }; return }
        val face = lastFaceBox
        var fx0 = 0f; var fx1 = 0f; var fy0 = 0f; var fy1 = 0f
        if (face != null) {
            val mx = face.width() * 0.4f / uw
            val my = face.height() * 0.4f / uh
            fx0 = face.left / uw - mx; fx1 = face.right / uw + mx
            fy0 = face.top / uh - my; fy1 = face.bottom / uh + my
        }
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        for (i in grid.indices) {
            val diff = abs(grid[i] - back[i])
            val isForeground = diff > 34
            // The room adapts quickly (lighting drifts), something held up only slowly (so he keeps seeing it).
            back[i] += (grid[i] - back[i]) * (if (isForeground) 0.004f else 0.04f)
            if (!isForeground) continue
            val cx = (i % gw + 0.5f) / gw
            val cy = (i / gw + 0.5f) / gh
            val ux = when (rot) { 90 -> 1 - cy; 180 -> 1 - cx; 270 -> cy; else -> cx }
            val uy = when (rot) { 90 -> cx; 180 -> 1 - cy; 270 -> 1 - cx; else -> cy }
            if (face != null && ux in fx0..fx1 && uy in fy0..fy1) continue   // that's you
            count += 1; sumX += ux; sumY += uy
        }
        val share = count / grid.size.toFloat()
        if (share > 0.55f) {   // the light changed or the phone moved: start over
            for (i in grid.indices) back[i] = grid[i].toFloat()
            objectRun = 0; objectVisible = false
            return
        }
        if (share < 0.03f) { objectRun = 0; objectVisible = false; return }
        objectRun += 1
        if (objectRun < 3) return   // a flicker is not an object
        objectVisible = true
        if (clock - lastObjectReport < 120) return
        val isNew = clock - lastObjectSeen > 1500
        lastObjectSeen = clock
        lastObjectReport = clock
        val gx = ((0.5 - sumX / count) * 2 * 1.6).coerceIn(-1.0, 1.0)
        val gy = ((sumY / count - 0.5) * 2 * 1.4).coerceIn(-1.0, 1.0)
        main.post { onObject?.invoke(isNew, gx, gy) }
    }

    private fun encode(proxy: ImageProxy, maxSide: Int, quality: Int): String? = try {
        var bitmap = proxy.toBitmap()
        val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val longest = max(bitmap.width, bitmap.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}
