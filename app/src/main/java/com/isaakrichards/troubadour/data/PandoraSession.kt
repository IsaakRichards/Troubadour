package com.isaakrichards.troubadour.data

import android.content.Context
import com.isaakrichards.troubadour.pandora.AudioQuality
import com.isaakrichards.troubadour.pandora.PandoraClient
import com.isaakrichards.troubadour.pandora.PandoraException
import com.isaakrichards.troubadour.pandora.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One Pandora session shared by the UI and the playback service (same process).
 * Handles saved logins and quietly re-authenticates when Pandora's token expires.
 */
object PandoraSession {
    private val client = PandoraClient()
    private val loginLock = Mutex()
    private lateinit var store: CredentialStore

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun init(context: Context) {
        if (!::store.isInitialized) store = CredentialStore(context.applicationContext)
    }

    var quality: AudioQuality
        get() = store.quality
        set(value) { store.quality = value }

    var lastStationToken: String?
        get() = store.lastStationToken
        set(value) { store.lastStationToken = value }

    val hasSavedLogin: Boolean get() = store.load() != null

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        loginLock.withLock { client.login(username, password) }
        store.save(username, password)
        _loggedIn.value = true
        refreshStations()
    }

    /** Signs in with the saved login if needed. Returns false when there is none. */
    suspend fun ensureLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        if (client.isLoggedIn) return@withContext true
        val (user, pass) = store.load() ?: return@withContext false
        loginLock.withLock {
            if (!client.isLoggedIn) client.login(user, pass)
        }
        _loggedIn.value = true
        true
    }

    /** Runs a Pandora call, re-logging in once if the session went stale. */
    suspend fun <T> call(block: PandoraClient.() -> T): T = withContext(Dispatchers.IO) {
        if (!ensureLoggedIn()) {
            throw PandoraException(PandoraException.NOT_LOGGED_IN, "Not logged in")
        }
        try {
            client.block()
        } catch (e: PandoraException) {
            if (e.code != PandoraException.INVALID_AUTH_TOKEN) throw e
            val (user, pass) = store.load() ?: throw e
            loginLock.withLock { client.login(user, pass) }
            client.block()
        }
    }

    suspend fun refreshStations(): List<Station> =
        call { getStations() }.also { _stations.value = it }

    fun logout() {
        client.logout()
        store.clear()
        _stations.value = emptyList()
        _loggedIn.value = false
    }

    fun describe(e: Throwable): String = when (e) {
        is PandoraException -> e.friendlyMessage
        is IOException -> "Couldn't reach Pandora. Check your connection."
        else -> e.message ?: "Something went wrong."
    }
}
