package com.isaakrichards.troubadour.pandora

/** Partner (device) credentials. These are the public Android partner keys. */
data class PartnerInfo(
    val username: String,
    val password: String,
    val deviceModel: String,
    val decryptKey: String,
    val encryptKey: String,
    val version: String = "5",
) {
    companion object {
        val ANDROID = PartnerInfo(
            username = "android",
            password = "AC7IBG09A3DTSYM4R41UJWL07VLN8JI7",
            deviceModel = "android-generic",
            decryptKey = "R=U!LH\$O2B#",
            encryptKey = "6#26FRL\$ZWD",
        )
    }
}

data class Station(
    val id: String,
    val token: String,
    val name: String,
    val artUrl: String?,
    val isQuickMix: Boolean,
)

enum class AudioQuality(val label: String) {
    HIGH("High (AAC)"),
    MP3("MP3 128 kbps"),
    MEDIUM("Medium"),
    LOW("Low (saves data)"),
}

data class Track(
    val trackToken: String,
    val stationToken: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val lengthSeconds: Int,
    /** 1 = thumbs up, 0 = unrated. */
    val rating: Int,
    val allowFeedback: Boolean,
    val urlHigh: String?,
    val urlMedium: String?,
    val urlLow: String?,
    val urlMp3: String?,
) {
    /** Preferred URL for the requested quality, falling back to whatever exists. */
    fun audioUrl(quality: AudioQuality): String? {
        val order = when (quality) {
            AudioQuality.HIGH -> listOf(urlHigh, urlMp3, urlMedium, urlLow)
            AudioQuality.MP3 -> listOf(urlMp3, urlHigh, urlMedium, urlLow)
            AudioQuality.MEDIUM -> listOf(urlMedium, urlHigh, urlMp3, urlLow)
            AudioQuality.LOW -> listOf(urlLow, urlMedium, urlHigh, urlMp3)
        }
        return order.firstOrNull { !it.isNullOrBlank() }
    }
}

data class SearchResult(
    val musicToken: String,
    val name: String,
    /** "Artist" or "Song by <artist>". */
    val description: String,
    val isArtist: Boolean,
)

class PandoraException(val code: Int, message: String) : Exception(message) {
    /** A message fit to show a person. */
    val friendlyMessage: String
        get() = when (code) {
            INVALID_LOGIN -> "Wrong email or password."
            INVALID_AUTH_TOKEN -> "Your Pandora session expired. Please try again."
            MAINTENANCE_MODE -> "Pandora is down for maintenance. Try again later."
            COUNTRY_NOT_SUPPORTED -> "Pandora isn't available in your country."
            BAD_SYNC_TIME, INSUFFICIENT_CONNECTIVITY -> "Couldn't sync with Pandora. Check your connection and the phone's clock."
            LISTENER_NOT_AUTHORIZED -> "This account isn't allowed to listen right now. Sign in on pandora.com to check it."
            MAX_STATIONS_REACHED -> "You've reached Pandora's station limit. Delete one first."
            STATION_DOES_NOT_EXIST -> "That station no longer exists."
            PLAYLIST_EXCEEDED -> "Pandora says you've requested too many songs. Take a short break and try again."
            NOT_LOGGED_IN -> "Please sign in first."
            else -> message?.takeIf { it.isNotBlank() } ?: "Pandora error $code"
        }

    companion object {
        const val INTERNAL = 0
        const val MAINTENANCE_MODE = 1
        const val COUNTRY_NOT_SUPPORTED = 12
        const val INSUFFICIENT_CONNECTIVITY = 13
        const val INVALID_AUTH_TOKEN = 1001
        const val INVALID_LOGIN = 1002
        const val LISTENER_NOT_AUTHORIZED = 1003
        const val MAX_STATIONS_REACHED = 1005
        const val STATION_DOES_NOT_EXIST = 1006
        const val PLAYLIST_EXCEEDED = 1039

        // Local codes, never sent by Pandora.
        const val NOT_LOGGED_IN = -100
        const val BAD_SYNC_TIME = -101
        const val BAD_RESPONSE = -102
    }
}
