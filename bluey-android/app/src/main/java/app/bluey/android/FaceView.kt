package app.bluey.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, val stiffness: Double = 200.0, val damping: Double = 0.7) {
    var velocity = 0.0
    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

/** Smooths what the Mac asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops. */
class FaceAnimator {
    data class Frame(
        val gazeX: Double, val gazeY: Double, val mood: Mood, val talk: Double, val closed: Double,
        val breathe: Double, val time: Double, val pupil: Double, val browLift: Double, val browTilt: Double,
        val lean: Double, val hop: Double, val blush: Double, val squint: Double,
    )

    private var target = FaceState()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood = Mood.LISTENING
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 5.0
    private var reactUntil = 0.0
    /** In duo mode: -1 (partner on the left) or 1 (on the right), otherwise null. */
    @Volatile var duoGaze: Double? = null
    @Volatile var duoGazeY: Double = -0.05
    private var reactX = 0.0
    private var reactY = 0.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderX = 0.0
    private var wanderY = -0.3
    private var jitterX = 0.0
    private var jitterY = 0.0
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Mood set on the phone itself (waking up, talking). */
    @Volatile var localMood: Mood? = null
    /** Loudness of the chirp playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }
    /** True while he's awake and talking with you. */
    @Volatile var awake = false

    /** Something moved or was held up in front of the camera: eyes wide, brows up, a little hop, and he looks at it. */
    fun react(strength: Double, gazeX: Double, gazeY: Double, now: Double) {
        reactUntil = now + 1.3
        reactX = gazeX
        reactY = gazeY
        hop.velocity += 200 + 300 * strength
        pupil.velocity += 4.0
        browLift.velocity += 120.0
    }

    /** Keeps looking toward something (an object that is held up) for a moment. */
    fun lookAt(gazeX: Double, gazeY: Double, seconds: Double, now: Double) {
        reactUntil = max(reactUntil, now + seconds)
        reactX = gazeX
        reactY = gazeY
    }

    /** The other Bluey said something: a little nod. */
    fun duoNod() {
        hop.velocity += 240.0
        browLift.velocity += 100.0
    }

    /** A tap on the screen: a happy little hop and rosy cheeks. */
    fun boop() {
        hop.velocity += 380.0
        blush.value = 0.9
        pupil.velocity += 3.0
    }

    fun receive(face: FaceState, now: Double) { target = face; lastPacket = now }

    private fun rnd(a: Double, b: Double) = a + Random.nextDouble() * (b - a)

    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20)
        lastTime = now

        val live = now - lastPacket < 2.5
        val local = localMood
        val wantedMood = local ?: (if (live) target.mood else Mood.LISTENING)
        if (wantedMood != mood) {
            mood = wantedMood
            if (mood != Mood.SLEEPY && mood != Mood.RESTING) { hop.velocity += 260; pupil.velocity += 3 }
        }

        var wantX: Double
        var wantY: Double
        if (live) {
            wantX = target.gazeX; wantY = target.gazeY
        } else {
            if (now > nextSaccade) {
                wanderX = rnd(-0.9, 0.9); wanderY = rnd(-0.9, 0.4)
                nextSaccade = now + rnd(0.6, 2.2)
            }
            wantX = wanderX; wantY = wanderY
        }
        if (mood == Mood.THINKING) { wantX = 0.6 + 0.08 * sin(now * 1.3); wantY = -0.85 }
        // Something was shown or moved in front of the camera: look at it.
        duoGaze?.let { if (now >= reactUntil) { wantX = it * 0.95; wantY = duoGazeY } }
        if (now < reactUntil) { wantX = reactX; wantY = reactY }
        if (now > nextJitter) {
            val amount = if (!awake) 0.015 else if (mood == Mood.TALKING) 0.07 else if (mood == Mood.THINKING) 0.05 else 0.02
            jitterX = rnd(-amount, amount); jitterY = rnd(-amount, amount)
            nextJitter = now + rnd(0.5, 1.4)
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        var wantTalk = if (live) target.talk else (if (local == Mood.TALKING) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0)
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        var wantPupil = 1.0; var wantLift = 0.0; var wantTilt = 0.0; var wantBlush = 0.25; var wantSquint = 0.0
        when (mood) {
            Mood.LISTENING -> { wantPupil = 1.12; wantLift = 10.0 }
            Mood.TALKING -> { wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3) }
            Mood.POINTING -> { wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14 }
            Mood.THINKING -> { wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22 }
            Mood.HAPPY -> { wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0 }
            Mood.RESTING -> { wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15 }
            Mood.SLEEPY -> { wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15 }
        }
        if (now < reactUntil) { wantPupil = 1.28; wantLift = 22.0 }
        pupil.step(wantPupil, dt); browLift.step(wantLift, dt); browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt); squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.SLEEPY && mood != Mood.RESTING) hop.velocity += rnd(180.0, 320.0)
            nextHop = now + rnd(7.0, 14.0)
        }
        hop.step(0.0, dt)

        val drowsy = mood == Mood.SLEEPY
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && Random.nextDouble() < 0.08
            nextBlink = now + (if (drowsy) rnd(3.0, 5.0) else rnd(5.0, 11.0))
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * (if (mood == Mood.RESTING) 1.1 else 1.8))
        return Frame(gazeX.value, gazeY.value, mood, talk, closed, breathe, now, pupil.value, browLift.value,
            browTilt.value, lean.value, hop.value, blush.value, squint.value.coerceIn(0.0, 1.0))
    }
}

// The blob outline: CSS border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%
internal fun blobPath(r: Rect): Path {
    val w = r.width; val h = r.height
    val tlw = 0.52f * w; val tlh = 0.58f * h
    val trw = 0.48f * w; val trh = 0.56f * h
    val brw = 0.46f * w; val brh = 0.44f * h
    val blw = 0.54f * w; val blh = 0.42f * h
    val k = 0.5523f
    return Path().apply {
        moveTo(r.left + tlw, r.top)
        lineTo(r.right - trw, r.top)
        cubicTo(r.right - trw * (1 - k), r.top, r.right, r.top + trh * (1 - k), r.right, r.top + trh)
        lineTo(r.right, r.bottom - brh)
        cubicTo(r.right, r.bottom - brh * (1 - k), r.right - brw * (1 - k), r.bottom, r.right - brw, r.bottom)
        lineTo(r.left + blw, r.bottom)
        cubicTo(r.left + blw * (1 - k), r.bottom, r.left, r.bottom - blh * (1 - k), r.left, r.bottom - blh)
        lineTo(r.left, r.top + tlh)
        cubicTo(r.left, r.top + tlh * (1 - k), r.left + tlw * (1 - k), r.top, r.left + tlw, r.top)
        close()
    }
}

private fun crownPath(r: Rect): Path {
    val pts = listOf(30f to 4f, 36f to 16f, 52f to 12f, 40f to 24f, 46f to 36f, 30f to 28f, 14f to 36f, 20f to 24f, 8f to 12f, 24f to 16f)
    return Path().apply {
        pts.forEachIndexed { i, (x, y) ->
            val px = r.left + x / 60f * r.width; val py = r.top + y / 40f * r.height
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

internal fun skinBrush(body: Rect) = Brush.linearGradient(
    colorStops = Palette.gradientStops.zip(Palette.gradient).toTypedArray(),
    start = Offset(body.left + 0.25f * body.width, body.top + 0.067f * body.height),
    end = Offset(body.left + 0.75f * body.width, body.top + 0.933f * body.height),
)

private fun shineBrush(body: Rect) = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
    center = Offset(body.left + 246f, body.top + 126f), radius = 243f,
)

private fun DrawScope.fillSkin(path: Path, body: Rect) {
    drawPath(path, skinBrush(body))
    drawPath(path, shineBrush(body))
}

private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
    Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }

/** The landscape face on pure black: he fills the screen and peeks up from the bottom edge. */
@Composable
fun FaceView(animator: FaceAnimator, modifier: Modifier = Modifier) {
    var now by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { now = it / 1e9 }
    }
    Canvas(modifier.fillMaxSize().background(Color.Black)) {
        val frame = animator.step(now)
        val designW = 844f; val designH = 390f
        val scale = max(size.width / designW, size.height / designH)
        withTransform({
            translate((size.width - designW * scale) / 2, size.height - designH * scale)
            scale(scale, scale, Offset.Zero)
        }) { drawFace(frame) }
    }
}

private fun DrawScope.drawFace(f: FaceAnimator.Frame) {
    val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
    val body = Rect(12f, 44f + bounce, 12f + 820f, 44f + bounce + 700f)
    val pivot = Offset(body.center.x, body.bottom)
    val stretch = (1 + (f.hop / 900).coerceIn(-0.03, 0.03)).toFloat()

    withTransform({
        translate(pivot.x, pivot.y)
        rotate((f.lean * 180 / PI).toFloat(), Offset.Zero)
        scale(2 - stretch, stretch, Offset.Zero)
        translate(-pivot.x, -pivot.y)
    }) {
        val blob = blobPath(body)
        val navy = Palette.nose
        val ink = Palette.ink

        // Soft glow behind him.
        drawCircle(
            Brush.radialGradient(listOf(Palette.berry2.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(body.center.x, body.top + 160f), radius = 520f),
            radius = 520f, center = Offset(body.center.x, body.top + 160f),
        )
        drawPath(blob, skinBrush(body))
        clipPath(blob) {
            drawPath(blob, shineBrush(body))
            drawOval(Color.White.copy(alpha = 0.35f), Offset(body.left + 190f, body.top + 40f), Size(46f, 22f))
        }
        drawPath(crownPath(Rect(body.left + 368f, body.top - 20f - (f.browLift * 0.3).toFloat(),
            body.left + 368f + 84f, body.top - 20f - (f.browLift * 0.3).toFloat() + 56f)), navy)

        val eyeY = body.top + 157f
        val eyes = listOf(Offset(body.left + 280f, eyeY), Offset(body.left + 540f, eyeY))
        val squash = (1 - f.talk * 0.12).toFloat()

        // Blushy cheeks, rosier when he's happy.
        eyes.forEachIndexed { i, c ->
            val side = if (i == 0) -1f else 1f
            val cx = c.x + side * 62f; val cy = c.y + 96f + 21f
            val alpha = (0.25 + 0.45 * f.blush).toFloat()
            drawOval(
                Brush.radialGradient(listOf(Color(0xFFF3A6D8).copy(alpha = alpha), Color.Transparent), center = Offset(cx, cy), radius = 58f),
                Offset(cx - 58f, cy - 36f), Size(116f, 72f),
            )
        }

        eyes.forEachIndexed { i, c ->
            val side = if (i == 0) -1f else 1f

            // Little arched eyebrows do a lot of the acting.
            val browY = c.y - 112f - f.browLift.coerceIn(-10.0, 18.0).toFloat() * 0.7f
            val tilt = f.browTilt * side * -1 + (if (f.mood == Mood.THINKING && i == 1) -0.25 else 0.0)
            withTransform({
                translate(c.x + side * 6f, browY)
                rotate((tilt * 180 / PI).toFloat(), Offset.Zero)
            }) {
                val arch = Path().apply { moveTo(-38f, 6f); quadraticBezierTo(0f, -12f, 38f, 6f) }
                drawPath(arch, navy, style = Stroke(14f, cap = StrokeCap.Round))
            }

            when {
                f.mood == Mood.RESTING -> {
                    val shut = Path().apply { moveTo(c.x - 62f, c.y + 6f); quadraticBezierTo(c.x, c.y + 52f, c.x + 62f, c.y + 6f) }
                    drawPath(shut, ink, style = Stroke(18f, cap = StrokeCap.Round))
                }
                f.mood == Mood.HAPPY && f.squint > 0.6 -> {
                    val arc = Path().apply { moveTo(c.x - 60f, c.y + 34f); quadraticBezierTo(c.x, c.y - 54f, c.x + 60f, c.y + 34f) }
                    drawPath(arc, ink, style = Stroke(24f, cap = StrokeCap.Round))
                }
                else -> {
                    val open = (max(0.06, 1 - f.closed)).toFloat() * squash
                    val whiteTop = c.y - 95f * open
                    val whiteH = 190f * open
                    val whitePath = Path().apply { addOval(Rect(c.x - 95f, whiteTop, c.x + 95f, whiteTop + whiteH)) }
                    drawPath(whitePath, Color.White)
                    if (open <= 0.2f) return@forEachIndexed

                    var gx = f.gazeX.toFloat(); var gy = f.gazeY.toFloat()
                    val len = hypot(gx, gy)
                    if (len > 1f) { gx /= len; gy /= len }
                    val reach = 50f
                    val r = (46 * f.pupil).toFloat()
                    var px = c.x + gx * reach - side * 3f
                    var py = c.y + gy * reach * open
                    if (f.mood == Mood.SLEEPY) py = max(py, c.y) + 28f
                    clipPath(whitePath) {
                        drawCircle(ink, r, Offset(px, py))
                        drawOval(Color.White, Offset(px - r * 0.58f, py - r * 0.72f), Size(r * 0.56f, r * 0.56f))
                        drawOval(Color.White.copy(alpha = 0.85f), Offset(px + r * 0.28f, py + r * 0.22f), Size(r * 0.24f, r * 0.24f))
                        if (f.squint > 0.02) {
                            val lidTop = c.y + 95f * open - 70f * f.squint.toFloat()
                            fillSkin(Path().apply { addOval(Rect(c.x - 110f, lidTop, c.x + 110f, lidTop + 160f)) }, body)
                        }
                        if (f.mood == Mood.SLEEPY) {
                            val droop = (0.5 + 0.06 * sin(f.time * 1.3)).toFloat()
                            fillSkin(Path().apply { addRect(Rect(c.x - 100f, whiteTop - 10f, c.x + 100f, whiteTop - 10f + whiteH * droop + 10f)) }, body)
                            val ly = whiteTop + whiteH * droop
                            drawLine(navy.copy(alpha = 0.7f), Offset(c.x - 92f, ly), Offset(c.x + 92f, ly), 8f, StrokeCap.Round)
                        }
                        if (f.mood == Mood.POINTING) {
                            fillSkin(Path().apply { addRect(Rect(c.x - 100f, whiteTop - 20f, c.x + 100f, whiteTop + 20f)) }, body)
                        }
                    }
                }
            }
        }

        if (f.mood == Mood.RESTING) {
            for (i in 0 until 3) {
                val phase = ((f.time * 0.35 + i / 3.0) % 1.0).toFloat()
                val sz = 26f + i * 8f
                val x = body.left + 660f + phase * 70f + sin(phase * 6) * 8f
                val y = body.top + 90f - phase * 110f
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = sz; isFakeBoldText = true
                    color = android.graphics.Color.argb((255 * 0.9 * sin(PI * phase)).toInt().coerceIn(0, 255), 255, 255, 255)
                }
                drawContext.canvas.nativeCanvas.drawText("z", x, y, paint)
            }
        }

        if (f.mood == Mood.THINKING) {
            for (i in 0 until 3) {
                val wobble = (sin(f.time * 3 + i) * 4).toFloat()
                drawOval(Color.White.copy(alpha = (1 - i * 0.3).toFloat()),
                    Offset(body.left + 690f + i * 26f, body.top + 40f - i * 12f + wobble), Size(16f, 16f))
            }
        }

        // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
        val noseW = (60 - f.talk * 8).toFloat()
        val noseH = (38 + f.talk * 34).toFloat()
        val noseLeft = body.left + 410f - noseW / 2
        val noseTop = body.top + 272f - (f.talk * 6).toFloat()
        if (f.mood == Mood.HAPPY && f.talk < 0.1) {
            val smile = Path().apply {
                moveTo(noseLeft - 8f, noseTop + 8f)
                quadraticBezierTo(noseLeft + noseW / 2, noseTop + 58f, noseLeft + noseW + 8f, noseTop + 8f)
                close()
            }
            drawPath(smile, navy)
        } else {
            drawOval(navy, Offset(noseLeft, noseTop), Size(noseW, noseH))
            if (f.talk > 0.25) {
                drawOval(Color(0xFFE58BC4).copy(alpha = 0.9f),
                    Offset(noseLeft + noseW / 2 - noseW * 0.28f, noseTop + noseH - noseH * 0.38f),
                    Size(noseW * 0.56f, noseH * 0.3f))
            }
        }
    }
}
