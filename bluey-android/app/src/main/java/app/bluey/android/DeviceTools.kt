package app.bluey.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Things he can do on the phone itself. [run] returns false for tools it doesn't know; otherwise it calls [done]
 * exactly once (maybe later, from another thread).
 */
class DeviceTools(private val context: Context, private val settings: Settings, private val voice: VoiceID) {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    fun run(name: String, args: JSONObject, done: (String) -> Unit): Boolean {
        when (name) {
            "get_time" -> done(SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.forLanguageTag(settings.lang.tag)).format(Date()))
            "get_weather" -> weather(done)
            "get_location" -> location(done)
            "set_timer" -> done(timer(args.optInt("seconds", 0), args.optString("label", "")))
            "set_alarm" -> done(alarm(args.optInt("hour", -1), args.optInt("minute", 0), args.optString("label", "")))
            "flashlight" -> done(torch(args.optBoolean("on", true)))
            "remember" -> {
                val note = args.optString("note", "").trim()
                if (note.isEmpty()) done("Nichts zum Merken angegeben.") else { settings.addNote(note); done("Gemerkt.") }
            }
            "forget" -> {
                val removed = settings.removeNotes(args.optString("contains", ""))
                done(if (removed > 0) "Vergessen ($removed Notiz(en))." else "Dazu hatte ich nichts notiert.")
            }
            "learn_my_voice" -> {
                val who = args.optString("name", "").trim()
                if (who.isEmpty()) done("Ich brauche den Namen der Person.")
                else {
                    voice.startEnroll(who, ownRecording = false)
                    done("Ich lerne jetzt die Stimme von $who. Sag der Person, sie soll etwa 20 Sekunden einfach drauflos erzählen. Ich melde mich, wenn ich fertig bin.")
                }
            }
            else -> return false
        }
        return true
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    private fun timer(seconds: Int, label: String): String {
        if (seconds <= 0) return "Ich brauche eine Dauer in Sekunden."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(intent)) "Timer läuft: $seconds Sekunden." else "Auf diesem Handy konnte ich keinen Timer stellen."
    }

    private fun alarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "Ich brauche eine gültige Uhrzeit."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(intent)) "Wecker gestellt auf %02d:%02d.".format(hour, minute) else "Auf diesem Handy konnte ich keinen Wecker stellen."
    }

    private fun torch(on: Boolean): String = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            val c = manager.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) "Dieses Handy hat keine Taschenlampe."
        else { manager.setTorchMode(id, on); if (on) "Taschenlampe an." else "Taschenlampe aus." }
    } catch (_: Exception) { "Die Taschenlampe geht gerade nicht." }

    // Weather at the phone's position (Open-Meteo, no key needed)

    private fun locationAllowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Roughly where the phone is, found in the background so he knows it when a conversation starts. */
    @Volatile var place: String? = null; private set

    fun refreshPlace() {
        if (!locationAllowed()) return
        findLocation { loc ->
            if (loc != null) Thread {
                val name = placeName(loc.latitude, loc.longitude)
                place = if (name == "deinem Standort") null else name
            }.start()
        }
    }

    /** "Where am I?": the address as far as the phone can tell. */
    private fun location(done: (String) -> Unit) {
        if (!locationAllowed()) { done("Ich darf deinen Standort nicht sehen. Erlaube ihn in den Handy-Einstellungen für Bluey."); return }
        findLocation { loc ->
            if (loc == null) { done("Ich finde deinen Standort gerade nicht. Ist der Standort am Handy eingeschaltet?"); return@findLocation }
            Thread { done(describeLocation(loc)) }.start()
        }
    }

    @Suppress("DEPRECATION")
    private fun describeLocation(loc: Location): String = try {
        val a = if (Geocoder.isPresent()) Geocoder(context, Locale.GERMANY).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() else null
        val line = a?.getAddressLine(0)
        val where = line ?: "bei %.4f, %.4f".format(Locale.US, loc.latitude, loc.longitude)
        "Das Handy ist gerade hier: $where (ungefähr, auf etwa ${loc.accuracy.roundToInt()} Meter genau)."
    } catch (_: Exception) {
        "Das Handy ist bei %.4f, %.4f.".format(Locale.US, loc.latitude, loc.longitude)
    }

    private fun weather(done: (String) -> Unit) {
        val allowed = locationAllowed()
        if (!allowed) { done("Ich darf deinen Standort nicht sehen. Erlaube ihn in den Handy-Einstellungen für Bluey."); return }
        findLocation { location ->
            if (location == null) { done("Ich finde deinen Standort gerade nicht. Ist der Standort am Handy eingeschaltet?"); return@findLocation }
            Thread { done(fetchWeather(location.latitude, location.longitude)) }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findLocation(callback: (Location?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = try { manager.getProviders(true) } catch (_: Exception) { emptyList<String>() }
        val last = providers.mapNotNull { try { manager.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) { callback(last); return }
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val provider = if (providers.contains(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else providers.first()
            var answered = false
            val finish = { location: Location? -> if (!answered) { answered = true; callback(location ?: last) } }
            try {
                manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location -> finish(location) }
            } catch (_: Exception) { finish(null) }
            Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 10_000)
            return
        }
        callback(last)
    }

    private fun fetchWeather(lat: Double, lon: Double): String = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2&wind_speed_unit=kmh"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) return "Das Wetter kam nicht an (${response.code})."
            val json = JSONObject(body)
            val now = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            fun day(i: Int): String {
                val low = daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
                val high = daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt()
                val rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0)
                return "${describe(daily.getJSONArray("weather_code").getInt(i))}, $low bis $high Grad, Regenwahrscheinlichkeit $rain Prozent"
            }
            val place = placeName(lat, lon)
            "Ort: $place. Jetzt: ${now.getDouble("temperature_2m").roundToInt()} Grad " +
                "(gefühlt ${now.getDouble("apparent_temperature").roundToInt()}), ${describe(now.getInt("weather_code"))}, " +
                "Wind ${now.getDouble("wind_speed_10m").roundToInt()} km/h. Heute: ${day(0)}. Morgen: ${day(1)}."
        }
    } catch (e: Exception) {
        "Das Wetter kam nicht an."
    }

    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String = try {
        if (!Geocoder.isPresent()) "deinem Standort"
        else {
            val a = Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: "deinem Standort"
        }
    } catch (_: Exception) { "deinem Standort" }

    private fun describe(code: Int) = when (code) {
        0 -> "klarer Himmel"
        1 -> "überwiegend klar"
        2 -> "teils bewölkt"
        3 -> "bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67 -> "Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Regenschauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "wechselhaft"
    }
}
