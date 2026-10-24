package app.greeny.android

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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
            "get_nearby" -> nearby(args, done)
            "show_on_map" -> done(showOnMap(args.optString("query", "")))
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


    // Places nearby: OpenStreetMap (Overpass), no key needed

    private val categories: Map<String, Pair<String, String>> = mapOf(
        "restaurant" to ("amenity" to "restaurant"),
        "cafe" to ("amenity" to "cafe"),
        "fast_food" to ("amenity" to "fast_food"),
        "bar" to ("amenity" to "bar|pub|biergarten"),
        "pharmacy" to ("amenity" to "pharmacy"),
        "supermarket" to ("shop" to "supermarket|convenience|kiosk"),
        "bakery" to ("shop" to "bakery"),
        "fuel" to ("amenity" to "fuel"),
        "atm" to ("amenity" to "atm"),
        "bank" to ("amenity" to "bank"),
        "hospital" to ("amenity" to "hospital"),
        "doctor" to ("amenity" to "doctors|clinic"),
        "toilet" to ("amenity" to "toilets"),
        "parking" to ("amenity" to "parking"),
        "charging" to ("amenity" to "charging_station"),
        "hotel" to ("tourism" to "hotel|hostel|guest_house"),
        "cinema" to ("amenity" to "cinema"),
        "bus_stop" to ("highway" to "bus_stop"),
        "station" to ("railway" to "station|tram_stop|subway_entrance"),
        "park" to ("leisure" to "park|playground"),
        "gym" to ("leisure" to "fitness_centre"),
        "post" to ("amenity" to "post_office|post_box"),
        "police" to ("amenity" to "police"),
    )
    private val unnamedOk = setOf("toilet", "parking", "atm", "charging", "post", "bus_stop")

    private class Place(val name: String, val meters: Int, val direction: String, val address: String, val hours: String)

    private fun nearby(args: JSONObject, done: (String) -> Unit) {
        if (!locationAllowed()) { done("Ich darf deinen Standort nicht sehen. Erlaube ihn in den Handy-Einstellungen für Greeny."); return }
        val category = args.optString("category", "").trim().lowercase()
        val name = args.optString("name", "").replace(Regex("[^\\p{L}\\p{N} &'-]"), " ").trim()
        val radius = args.optInt("radius_m", 1500).coerceIn(200, 5000)
        if (category.isEmpty() && name.isEmpty()) { done("Ich brauche eine Art von Ort oder einen Namen."); return }
        findLocation { loc ->
            if (loc == null) { done("Ich finde deinen Standort gerade nicht. Ist der Standort am Handy eingeschaltet?"); return@findLocation }
            Thread { done(searchNearby(loc.latitude, loc.longitude, category, name, radius)) }.start()
        }
    }

    private fun searchNearby(lat: Double, lon: Double, category: String, name: String, radius: Int): String {
        val tag = categories[category]
        val filter = StringBuilder()
        if (tag != null) filter.append("[\"${tag.first}\"~\"^(${tag.second})\$\"]")
        if (name.isNotEmpty()) filter.append("[\"name\"~\"$name\",i]")
        if (filter.isEmpty()) return "Diese Art von Ort kenne ich nicht. Ich kann Restaurants, Apotheken, Läden, Tankstellen, Toiletten, Haltestellen und mehr suchen."
        val what = if (name.isNotEmpty()) name else category
        for (r in listOf(radius, radius * 2, 5000).filter { it <= 5000 }.distinct()) {
            val json = overpass("[out:json][timeout:20];nwr(around:$r,$lat,$lon)$filter;out center 80;")
                ?: return "Die Ortssuche hat gerade nicht geklappt. Ist das Internet an?"
            val places = parsePlaces(json, lat, lon, category)
            if (places.isEmpty()) continue
            val lines = places.take(8).mapIndexed { i, p ->
                "${i + 1}. ${p.name}, ${p.meters} m Richtung ${p.direction}" +
                    (if (p.address.isNotEmpty()) ", ${p.address}" else "") +
                    (if (p.hours.isNotEmpty()) ", Öffnungszeiten: ${p.hours}" else "")
            }
            return "In der Nähe ($what, Umkreis $r m, laut OpenStreetMap; Angaben können veraltet sein):\n" + lines.joinToString("\n")
        }
        return "Im Umkreis von 5 Kilometern habe ich dazu nichts gefunden."
    }

    private fun overpass(query: String): JSONObject? {
        for (host in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try {
                val request = Request.Builder().url(host).header("User-Agent", "GreenyAndroid/1.0")
                    .post(FormBody.Builder().add("data", query).build()).build()
                http.newCall(request).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (r.isSuccessful) return JSONObject(text)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parsePlaces(json: JSONObject, lat: Double, lon: Double, category: String): List<Place> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val places = ArrayList<Place>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val centre = e.optJSONObject("center")
            val plat: Double
            val plon: Double
            if (e.has("lat")) { plat = e.getDouble("lat"); plon = e.getDouble("lon") }
            else if (centre != null) { plat = centre.getDouble("lat"); plon = centre.getDouble("lon") }
            else continue
            var name = tags.optString("name").ifEmpty { tags.optString("brand").ifEmpty { tags.optString("operator") } }
            if (name.isEmpty()) { if (category in unnamedOk) name = "(ohne Namen)" else continue }
            val street = tags.optString("addr:street")
            val address = if (street.isEmpty()) "" else (street + " " + tags.optString("addr:housenumber")).trim()
            places.add(Place(name, distanceMeters(lat, lon, plat, plon).roundToInt(), direction(lat, lon, plat, plon), address, tags.optString("opening_hours")))
        }
        return places.sortedBy { it.meters }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val a = s1 * s1 + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * s2 * s2
        return 2 * 6_371_000.0 * asin(sqrt(a))
    }

    private fun direction(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        val degrees = (Math.toDegrees(atan2(y, x)) + 360) % 360
        val words = listOf("Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten")
        return words[((degrees + 22.5) / 45).toInt() % 8]
    }

    private fun showOnMap(query: String): String {
        if (query.isBlank()) return "Ich brauche, was ich auf der Karte zeigen soll."
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        return if (start(intent)) "Ich zeige „$query“ auf der Karte." else "Auf diesem Handy gibt es keine Karten-App."
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
        if (!locationAllowed()) { done("Ich darf deinen Standort nicht sehen. Erlaube ihn in den Handy-Einstellungen für Greeny."); return }
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
        if (!allowed) { done("Ich darf deinen Standort nicht sehen. Erlaube ihn in den Handy-Einstellungen für Greeny."); return }
        findLocation { location ->
            if (location == null) { done("Ich finde deinen Standort gerade nicht. Ist der Standort am Handy eingeschaltet?"); return@findLocation }
            Thread {
                val place = placeName(location.latitude, location.longitude)
                val where = if (place == "deinem Standort") "%.3f, %.3f".format(Locale.US, location.latitude, location.longitude) else place
                val question = "Wie ist das Wetter jetzt, heute und morgen in $where? Nenne Temperatur, Wetterlage, Wind und Regenwahrscheinlichkeit."
                Agent.runTool(settings.apiKey, "web_research", JSONObject().put("question", question).toString(), settings.lang) { report ->
                    done("Ort: $place. $report")
                }
            }.start()
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

    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String = try {
        if (!Geocoder.isPresent()) "deinem Standort"
        else {
            val a = Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: "deinem Standort"
        }
    } catch (_: Exception) { "deinem Standort" }
}
