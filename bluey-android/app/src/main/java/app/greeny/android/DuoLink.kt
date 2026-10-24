package app.greeny.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject

enum class DuoState { OFF, WAITING, CONNECTING, CONNECTED }

class DuoPeer(val name: String, val skin: Skin)

/**
 * Duo room: up to four phones with a Greeny each. One phone opens the room (host), the others join. Google's Nearby
 * Connections finds the room over Bluetooth, no pairing needed. The host passes every line on and says who speaks
 * next, so the Greenys take turns and never talk over each other. The talk runs until someone stops it.
 */
class DuoLink(private val context: Context) {
    companion object {
        const val MAX_PEOPLE = 4
        private const val SERVICE_ID = "app.greeny.android.duo"
        private const val SEARCH_MS = 90_000L
        private const val TURN_TIMEOUT_MS = 30_000L
    }

    private val prefs = context.getSharedPreferences("greeny", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf(DuoState.OFF); private set
    var message by mutableStateOf(""); private set
    var isHost by mutableStateOf(false); private set
    /** The name this phone's Greeny has in the room (made unique if two phones have the same color). */
    var myName by mutableStateOf(Palette.skin.name); private set
    /** Everybody else in the room. */
    var peers by mutableStateOf<List<DuoPeer>>(emptyList()); private set

    private var sideState by mutableStateOf(prefs.getString("duoSide", "right") ?: "right")
    /** With one other phone: where it stands, "left" or "right". */
    var side: String
        get() = sideState
        set(value) { sideState = value; prefs.edit().putString("duoSide", value).apply() }

    /** A line was said (empty text: a turn was passed on). [next] is who speaks next. */
    var onLine: ((String, String, String) -> Unit)? = null
    /** The talk starts: (topic, who begins). */
    var onStart: ((String, String) -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    /** Asks for the permissions below; calls back with whether all were granted. */
    var requestPermission: ((Boolean) -> Unit) -> Unit = { done -> done(true) }

    private class Member(val name: String, val skin: Skin, val endpoint: String?)

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val members = ArrayList<Member>()          // host only: everybody, the host first
    private val pending = HashSet<String>()            // host only: connected, but no hello yet
    private val asked = HashSet<String>()
    private var hostEndpoint: String? = null           // guest only
    private var convo = false                          // host only: a talk is running
    private var expected = ""                          // host only: who is supposed to speak
    private val searchTimeout = Runnable {
        if (!isHost && state != DuoState.CONNECTED) {
            reset()
            message = "Ich habe keinen offenen Raum gefunden. Hat ein Handy „Raum öffnen“ gedrückt? Sind Bluetooth und Standort an?"
        }
    }
    private val turnTimeout = Runnable { skipTurn() }

    fun missingPermissions(): List<String> {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT <= 32) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun withPermission(then: () -> Unit) {
        if (missingPermissions().isEmpty()) { then(); return }
        requestPermission { ok ->
            if (ok) then() else message = "Ich brauche die Erlaubnis für Geräte in der Nähe (Bluetooth). Erlaube sie in den Handy-Einstellungen für Greeny."
        }
    }

    private fun ownName() = "${Palette.skin.id}|${Palette.skin.name}"

    /** This phone opens the room; up to three others can join. */
    fun openRoom() = withPermission {
        reset()
        isHost = true
        myName = Palette.skin.name
        members.add(Member(myName, Palette.skin, null))
        updatePeers()
        state = DuoState.WAITING
        message = "Der Raum ist offen. Die anderen Handys tippen auf „Raum beitreten“."
        try {
            client.startAdvertising(ownName(), SERVICE_ID, lifecycle,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
                .addOnFailureListener { e -> failed("Den Raum öffnen", e) }
        } catch (e: Exception) { failed("Den Raum öffnen", e) }
    }

    /** This phone looks for an open room and joins it. */
    fun joinRoom() = withPermission {
        reset()
        isHost = false
        state = DuoState.WAITING
        message = "Ich suche einen offenen Raum …"
        try {
            client.startDiscovery(SERVICE_ID, discovery,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
                .addOnFailureListener { e -> failed("Suchen", e) }
        } catch (e: Exception) { failed("Suchen", e) }
        main.removeCallbacks(searchTimeout)
        main.postDelayed(searchTimeout, SEARCH_MS)
    }

    private fun failed(what: String, e: Exception) {
        val code = (e as? ApiException)?.statusCode?.toString() ?: (e.message ?: "?")
        reset()
        message = "$what hat nicht geklappt ($code). Sind Bluetooth und Standort an, und gibt es die Google Play-Dienste?"
    }

    /** The callback, fetched through a function so the callbacks below can use it without referring to themselves while they are being built. */
    private fun lifecycleCb(): ConnectionLifecycleCallback = lifecycle

    private val discovery: EndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (isHost || hostEndpoint != null || asked.isNotEmpty()) return
            asked.add(endpointId)
            message = "Raum gefunden. Verbinde …"
            client.requestConnection(ownName(), endpointId, lifecycleCb()).addOnFailureListener { e ->
                asked.remove(endpointId)
                message = "Das Verbinden hat nicht geklappt (${(e as? ApiException)?.statusCode ?: e.message}). Ich versuche es weiter."
            }
        }

        override fun onEndpointLost(endpointId: String) { asked.remove(endpointId) }
    }

    private val lifecycle: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (isHost && members.size + pending.size >= MAX_PEOPLE) { client.rejectConnection(endpointId); return }
            if (!isHost) { state = DuoState.CONNECTING; message = "Verbinde …" }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                if (isHost) {
                    pending.add(endpointId)
                } else {
                    hostEndpoint = endpointId
                    try { client.stopDiscovery() } catch (_: Exception) {}
                    main.removeCallbacks(searchTimeout)
                    state = DuoState.CONNECTED
                    message = "Im Raum."
                    sendTo(endpointId, JSONObject().put("t", "hello").put("name", Palette.skin.name).put("skin", Palette.skin.id))
                }
            } else {
                asked.remove(endpointId)
                pending.remove(endpointId)
                if (!isHost) { state = DuoState.WAITING; message = "Die Verbindung kam nicht zustande (${result.status.statusCode}). Ich suche weiter." }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (isHost) {
                pending.remove(endpointId)
                val left = members.firstOrNull { it.endpoint == endpointId } ?: return
                members.remove(left)
                updatePeers()
                if (members.size <= 1) {
                    state = DuoState.WAITING
                    message = "Alle Gäste sind weg. Der Raum ist noch offen."
                    if (convo) hostStop()
                } else {
                    broadcastRoster()
                    if (expected == left.name) skipTurn()
                }
                try { client.startAdvertising(ownName(), SERVICE_ID, lifecycleCb(), AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()) } catch (_: Exception) {}
            } else if (endpointId == hostEndpoint) {
                val was = state == DuoState.CONNECTED
                reset()
                if (was) { message = "Der Raum wurde geschlossen."; onDisconnected?.invoke() }
            }
        }
    }

    private val payloads: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { return }
            if (isHost) hostReceived(endpointId, json) else guestReceived(json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // Host

    private fun uniqueName(base: String): String {
        var name = base
        var n = 2
        while (members.any { it.name == name }) { name = "$base $n"; n += 1 }
        return name
    }

    private fun hostReceived(endpoint: String, json: JSONObject) {
        when (json.optString("t")) {
            "hello" -> {
                if (members.size >= MAX_PEOPLE || members.any { it.endpoint == endpoint }) return
                val name = uniqueName(json.optString("name", "Greeny"))
                members.add(Member(name, Skins.byId(json.optString("skin")), endpoint))
                pending.remove(endpoint)
                sendTo(endpoint, JSONObject().put("t", "welcome").put("name", name))
                updatePeers()
                broadcastRoster()
                state = DuoState.CONNECTED
                message = "Im Raum: ${members.size} von $MAX_PEOPLE."
                if (members.size >= MAX_PEOPLE) try { client.stopAdvertising() } catch (_: Exception) {}
            }
            "say" -> members.firstOrNull { it.endpoint == endpoint }?.let { hostSay(it.name, json.optString("text")) }
            "start" -> members.firstOrNull { it.endpoint == endpoint }?.let { hostStart(json.optString("topic"), it.name) }
            "stop" -> hostStop()
        }
    }

    private fun nextAfter(name: String): String {
        if (members.isEmpty()) return ""
        val i = members.indexOfFirst { it.name == name }
        return members[(i + 1) % members.size].name
    }

    private fun armTurnTimer() {
        main.removeCallbacks(turnTimeout)
        if (convo) main.postDelayed(turnTimeout, TURN_TIMEOUT_MS)
    }

    private fun hostSay(from: String, text: String) {
        if (!convo) return
        val next = nextAfter(from)
        expected = next
        broadcast(JSONObject().put("t", "line").put("from", from).put("text", text).put("next", next))
        onLine?.invoke(from, text, next)
        armTurnTimer()
    }

    /** The one whose turn it is doesn't answer (hung, gone): pass the turn on, so the talk never stops by itself. */
    private fun skipTurn() {
        if (!convo || members.size < 2) return
        hostSay(expected, "")
    }

    private fun hostStart(topic: String, starter: String) {
        if (members.size < 2) return
        convo = true
        expected = starter
        broadcast(JSONObject().put("t", "start").put("topic", topic).put("first", starter))
        onStart?.invoke(topic, starter)
        main.removeCallbacks(turnTimeout)
        main.postDelayed(turnTimeout, TURN_TIMEOUT_MS + 15_000)
    }

    private fun hostStop() {
        convo = false
        main.removeCallbacks(turnTimeout)
        broadcast(JSONObject().put("t", "stop"))
        onStop?.invoke()
    }

    private fun broadcastRoster() {
        val list = JSONArray()
        for (m in members) list.put(JSONObject().put("name", m.name).put("skin", m.skin.id))
        broadcast(JSONObject().put("t", "roster").put("members", list))
    }

    private fun broadcast(json: JSONObject) {
        val ids = members.mapNotNull { it.endpoint }
        if (ids.isEmpty()) return
        try { client.sendPayload(ids, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))) } catch (_: Exception) {}
    }

    private fun updatePeers() {
        peers = members.filter { it.name != myName }.map { DuoPeer(it.name, it.skin) }
    }

    // Guest

    private fun guestReceived(json: JSONObject) {
        when (json.optString("t")) {
            "welcome" -> myName = json.optString("name", Palette.skin.name)
            "roster" -> {
                val arr = json.optJSONArray("members") ?: return
                peers = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optString("name") != myName }
                    .map { DuoPeer(it.optString("name"), Skins.byId(it.optString("skin"))) }
            }
            "line" -> onLine?.invoke(json.optString("from"), json.optString("text"), json.optString("next"))
            "start" -> onStart?.invoke(json.optString("topic"), json.optString("first"))
            "stop" -> onStop?.invoke()
        }
    }

    private fun sendTo(endpoint: String, json: JSONObject) {
        try { client.sendPayload(endpoint, Payload.fromBytes(json.toString().toByteArray(Charsets.UTF_8))) } catch (_: Exception) {}
    }

    // For the talk (used by LiveVoice)

    fun startConversation(topic: String) {
        if (isHost) hostStart(topic, myName) else hostEndpoint?.let { sendTo(it, JSONObject().put("t", "start").put("topic", topic)) }
    }

    fun sendLine(text: String) {
        if (isHost) hostSay(myName, text) else hostEndpoint?.let { sendTo(it, JSONObject().put("t", "say").put("text", text)) }
    }

    fun stopConversation() {
        if (isHost) hostStop() else hostEndpoint?.let { sendTo(it, JSONObject().put("t", "stop")) }
    }

    private fun reset() {
        main.removeCallbacks(searchTimeout)
        main.removeCallbacks(turnTimeout)
        try { client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints() } catch (_: Exception) {}
        members.clear(); pending.clear(); asked.clear()
        hostEndpoint = null
        convo = false
        isHost = false
        state = DuoState.OFF
        peers = emptyList()
        myName = Palette.skin.name
    }

    /** Leave the room (or close it, if this phone opened it). */
    fun close() {
        val was = state == DuoState.CONNECTED
        reset()
        message = ""
        if (was) onDisconnected?.invoke()
    }
}
