package com.isaakrichards.troubadour.pandora

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * A small, blocking client for Pandora's JSON "tuner" API (v5), the same one
 * Pithos and pianobar speak. Call it from a background thread.
 *
 * Ad entries in playlists are dropped, the same way Pithos handles them.
 */
class PandoraClient(
    private val partner: PartnerInfo = PartnerInfo.ANDROID,
    private val transport: Transport = HttpTransport(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun interface Transport {
        /** POST [body] to [url] and return the response text. */
        @Throws(IOException::class)
        fun post(url: String, body: String): String
    }

    @Volatile private var partnerId: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var userAuthToken: String? = null
    @Volatile private var timeOffset: Long = 0

    val isLoggedIn: Boolean get() = userAuthToken != null

    private fun syncTime(): Long = clock() + timeOffset

    @Synchronized
    fun login(username: String, password: String) {
        logout()

        val partnerResult = request(
            method = "auth.partnerLogin",
            body = JSONObject()
                .put("username", partner.username)
                .put("password", partner.password)
                .put("deviceModel", partner.deviceModel)
                .put("version", partner.version)
                .put("includeUrls", true),
            encrypted = false,
            authToken = null,
        )
        val newPartnerId = partnerResult.requireString("partnerId")
        val partnerAuthToken = partnerResult.requireString("partnerAuthToken")
        val serverTime = PandoraCrypto.decodeSyncTime(
            partnerResult.requireString("syncTime"), partner.decryptKey,
        )
        timeOffset = serverTime - clock()
        partnerId = newPartnerId

        val userResult = request(
            method = "auth.userLogin",
            body = JSONObject()
                .put("loginType", "user")
                .put("username", username)
                .put("password", password)
                .put("partnerAuthToken", partnerAuthToken)
                .put("syncTime", syncTime()),
            encrypted = true,
            authToken = partnerAuthToken,
        )
        if (!userResult.optBoolean("canListen", true)) {
            partnerId = null
            throw PandoraException(PandoraException.LISTENER_NOT_AUTHORIZED, "Account cannot listen")
        }
        userId = userResult.requireString("userId")
        userAuthToken = userResult.requireString("userAuthToken")
    }

    fun logout() {
        partnerId = null
        userId = null
        userAuthToken = null
        timeOffset = 0
    }

    fun getStations(): List<Station> {
        val result = userRequest(
            "user.getStationList",
            JSONObject().put("includeStationArtUrl", true),
        )
        val arr = result.optJSONArray("stations") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Station(
                id = s.optString("stationId"),
                token = s.optString("stationToken").ifEmpty { return@mapNotNull null },
                name = s.optString("stationName", "Untitled station"),
                artUrl = s.optString("artUrl").ifBlank { null },
                isQuickMix = s.optBoolean("isQuickMix", false),
            )
        }
    }

    fun getPlaylist(stationToken: String): List<Track> {
        val result = userRequest(
            "station.getPlaylist",
            JSONObject()
                .put("stationToken", stationToken)
                .put("includeTrackLength", true)
                .put("additionalAudioUrl", "HTTP_128_MP3"),
        )
        val items = result.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            if (item.has("adToken")) return@mapNotNull null // ads are simply not played
            parseTrack(item, stationToken)
        }
    }

    fun rate(stationToken: String, trackToken: String, positive: Boolean) {
        userRequest(
            "station.addFeedback",
            JSONObject()
                .put("stationToken", stationToken)
                .put("trackToken", trackToken)
                .put("isPositive", positive),
        )
    }

    /** "Tired of this song": Pandora shelves it for about a month. */
    fun sleepSong(trackToken: String) {
        userRequest("user.sleepSong", JSONObject().put("trackToken", trackToken))
    }

    fun search(text: String): List<SearchResult> {
        val result = userRequest("music.search", JSONObject().put("searchText", text))
        val out = mutableListOf<SearchResult>()
        result.optJSONArray("artists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val token = a.optString("musicToken")
                if (token.isEmpty()) continue
                out += SearchResult(token, a.optString("artistName"), "Artist", isArtist = true)
            }
        }
        result.optJSONArray("songs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val token = s.optString("musicToken")
                if (token.isEmpty()) continue
                out += SearchResult(
                    token, s.optString("songName"),
                    "Song by ${s.optString("artistName")}", isArtist = false,
                )
            }
        }
        return out
    }

    fun createStation(musicToken: String): Station {
        val s = userRequest("station.createStation", JSONObject().put("musicToken", musicToken))
        return Station(
            id = s.optString("stationId"),
            token = s.requireString("stationToken"),
            name = s.optString("stationName", "New station"),
            artUrl = s.optString("artUrl").ifBlank { null },
            isQuickMix = false,
        )
    }

    fun deleteStation(stationToken: String) {
        userRequest("station.deleteStation", JSONObject().put("stationToken", stationToken))
    }

    // --- plumbing ---

    private fun userRequest(method: String, body: JSONObject): JSONObject {
        val token = userAuthToken
            ?: throw PandoraException(PandoraException.NOT_LOGGED_IN, "Not logged in")
        body.put("userAuthToken", token).put("syncTime", syncTime())
        return request(method, body, encrypted = true, authToken = token)
    }

    private fun request(
        method: String,
        body: JSONObject,
        encrypted: Boolean,
        authToken: String?,
    ): JSONObject {
        val url = buildString {
            append(BASE_URL).append("?method=").append(method)
            partnerId?.let { append("&partner_id=").append(enc(it)) }
            userId?.let { append("&user_id=").append(enc(it)) }
            authToken?.let { append("&auth_token=").append(enc(it)) }
        }
        val payload = if (encrypted) PandoraCrypto.encrypt(body.toString(), partner.encryptKey) else body.toString()
        val text = transport.post(url, payload)
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw PandoraException(PandoraException.BAD_RESPONSE, "Unexpected reply from Pandora")
        }
        if (json.optString("stat") != "ok") {
            throw PandoraException(json.optInt("code", PandoraException.INTERNAL), json.optString("message"))
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun parseTrack(item: JSONObject, stationToken: String): Track? {
        val token = item.optString("trackToken").ifEmpty { return null }
        val map = item.optJSONObject("audioUrlMap")
        fun q(name: String): String? = map?.optJSONObject(name)?.optString("audioUrl")?.ifBlank { null }
        val mp3 = when (val extra = item.opt("additionalAudioUrl")) {
            is String -> extra.ifBlank { null }
            is JSONArray -> extra.optString(0).ifBlank { null }
            else -> null
        }
        return Track(
            trackToken = token,
            stationToken = stationToken,
            title = item.optString("songName"),
            artist = item.optString("artistName"),
            album = item.optString("albumName"),
            albumArtUrl = item.optString("albumArtUrl").ifBlank { null },
            lengthSeconds = item.optInt("trackLength", 0),
            rating = item.optInt("songRating", 0),
            allowFeedback = item.optBoolean("allowFeedback", true),
            urlHigh = q("highQuality"),
            urlMedium = q("mediumQuality"),
            urlLow = q("lowQuality"),
            urlMp3 = mp3,
        )
    }

    private fun JSONObject.requireString(key: String): String =
        optString(key).ifEmpty {
            throw PandoraException(PandoraException.BAD_RESPONSE, "Pandora reply missing $key")
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE_URL = "https://tuner.pandora.com/services/json/"
    }
}

/** Plain HttpURLConnection transport; no third-party HTTP library needed. */
class HttpTransport(private val timeoutMs: Int = 15_000) : PandoraClient.Transport {
    override fun post(url: String, body: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("User-Agent", "Troubadour/0.1 (Android)")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }
                ?: throw IOException("Empty response (HTTP ${conn.responseCode})")
        } finally {
            conn.disconnect()
        }
    }
}
