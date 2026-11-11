package com.isaakrichards.troubadour.pandora

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PandoraCryptoTest {
    @Test
    fun matchesSchneierBlowfishVector() {
        // Classic Blowfish test vector: key a..z, plaintext "BLOWFISH".
        val hex = PandoraCrypto.encrypt("BLOWFISH", "abcdefghijklmnopqrstuvwxyz")
        assertEquals("324ed0fef413a203", hex)
    }

    @Test
    fun roundTripsWithZeroPadding() {
        val key = PartnerInfo.ANDROID.encryptKey
        val text = """{"hello":"pandora","n":12345}"""
        val hex = PandoraCrypto.encrypt(text, key)
        assertEquals(0, (hex.length / 2) % 8)
        val back = String(PandoraCrypto.decrypt(hex, key), Charsets.UTF_8).trimEnd('\u0000')
        assertEquals(text, back)
    }

    @Test
    fun decodesSyncTime() {
        val key = PartnerInfo.ANDROID.decryptKey
        val hex = PandoraCrypto.encrypt("\u0007xyz1700000000", key)
        assertEquals(1_700_000_000L, PandoraCrypto.decodeSyncTime(hex, key))
    }
}

class PandoraClientTest {
    private val partner = PartnerInfo.ANDROID
    private val calls = mutableListOf<Pair<String, String>>()

    private fun decryptBody(body: String) =
        JSONObject(String(PandoraCrypto.decrypt(body, partner.encryptKey), Charsets.UTF_8).trimEnd('\u0000'))

    private fun ok(result: JSONObject) = JSONObject().put("stat", "ok").put("result", result).toString()

    private fun fakeServer(url: String, body: String): String {
        calls += url to body
        val method = Regex("method=([^&]+)").find(url)!!.groupValues[1]
        return when (method) {
            "auth.partnerLogin" -> ok(
                JSONObject()
                    .put("partnerId", "42")
                    .put("partnerAuthToken", "PAT/+=")
                    .put("syncTime", PandoraCrypto.encrypt("abcd1700000100", partner.decryptKey)),
            )
            "auth.userLogin" -> {
                val b = decryptBody(body)
                if (b.getString("password") != "right") {
                    JSONObject().put("stat", "fail").put("code", 1002).put("message", "INVALID_LOGIN").toString()
                } else ok(JSONObject().put("userId", "u7").put("userAuthToken", "UAT").put("canListen", true))
            }
            "station.getPlaylist" -> ok(
                JSONObject().put(
                    "items",
                    JSONArray()
                        .put(JSONObject().put("adToken", "AD1"))
                        .put(
                            JSONObject()
                                .put("trackToken", "T1").put("songName", "Greensleeves")
                                .put("artistName", "Anon").put("albumName", "Ballads")
                                .put("songRating", 1)
                                .put("audioUrlMap", JSONObject().put("highQuality", JSONObject().put("audioUrl", "https://a/high")))
                                .put("additionalAudioUrl", "https://a/mp3"),
                        ),
                ),
            )
            "user.getStationList" -> ok(
                JSONObject().put(
                    "stations",
                    JSONArray().put(
                        JSONObject().put("stationId", "1").put("stationToken", "S1").put("stationName", "Lute Radio"),
                    ),
                ),
            )
            else -> ok(JSONObject())
        }
    }

    private fun client() = PandoraClient(partner, { url, body -> fakeServer(url, body) }, { 1_700_000_000L })

    @Test
    fun logsInAndSignsRequests() {
        val c = client()
        c.login("king@example.com", "right")
        assertTrue(c.isLoggedIn)

        // partnerLogin is plain JSON, userLogin is encrypted with the partner token in the URL.
        assertEquals("android", JSONObject(calls[0].second).getString("username"))
        assertTrue(calls[1].first.contains("auth_token=PAT%2F%2B%3D"))
        assertTrue(calls[1].first.contains("partner_id=42"))
        assertEquals(1_700_000_100L, decryptBody(calls[1].second).getLong("syncTime"))

        val stations = c.getStations()
        assertEquals(listOf("Lute Radio"), stations.map { it.name })
        val last = calls.last()
        assertTrue(last.first.contains("user_id=u7"))
        assertTrue(last.first.contains("auth_token=UAT"))
        assertEquals("UAT", decryptBody(last.second).getString("userAuthToken"))
    }

    @Test
    fun dropsAdsFromPlaylist() {
        val c = client()
        c.login("king@example.com", "right")
        val tracks = c.getPlaylist("S1")
        assertEquals(1, tracks.size)
        val t = tracks.single()
        assertEquals("Greensleeves", t.title)
        assertEquals(1, t.rating)
        assertEquals("https://a/high", t.audioUrl(AudioQuality.HIGH))
        assertEquals("https://a/mp3", t.audioUrl(AudioQuality.MP3))
        assertEquals("https://a/high", t.audioUrl(AudioQuality.LOW)) // falls back
        assertEquals(listOf("https://a/mp3", "https://a/high"), t.audioUrls(AudioQuality.MP3))
    }

    @Test
    fun reportsBadPassword() {
        val c = client()
        try {
            c.login("king@example.com", "wrong")
            fail("expected failure")
        } catch (e: PandoraException) {
            assertEquals(PandoraException.INVALID_LOGIN, e.code)
            assertEquals("Wrong email or password.", e.friendlyMessage)
        }
        assertFalse(c.isLoggedIn)
    }
}
