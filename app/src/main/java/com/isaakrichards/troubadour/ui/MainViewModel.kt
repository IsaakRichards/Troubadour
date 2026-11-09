package com.isaakrichards.troubadour.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ThumbRating
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.isaakrichards.troubadour.data.PandoraSession
import com.isaakrichards.troubadour.pandora.AudioQuality
import com.isaakrichards.troubadour.pandora.SearchResult
import com.isaakrichards.troubadour.pandora.Station
import com.isaakrichards.troubadour.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlaying(
    val hasItem: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val thumbsUp: Boolean = false,
    val stationToken: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    init {
        PandoraSession.init(app)
    }

    val loggedIn: StateFlow<Boolean> = PandoraSession.loggedIn
    val stations: StateFlow<List<Station>> = PandoraSession.stations

    private val _checking = MutableStateFlow(PandoraSession.hasSavedLogin)
    /** True while restoring a saved login at startup. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val _quality = MutableStateFlow(PandoraSession.quality)
    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var searchJob: Job? = null

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    )
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                extras.getString(PlaybackService.EXTRA_ERROR)?.let { _error.value = it }
            }
        })
        .buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshNowPlaying()
    }

    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.getOrNull()?.let {
                controller = it
                it.addListener(playerListener)
                refreshNowPlaying()
            }
        }, ContextCompat.getMainExecutor(app))

        if (_checking.value) {
            viewModelScope.launch {
                try {
                    if (PandoraSession.ensureLoggedIn()) PandoraSession.refreshStations()
                } catch (e: Exception) {
                    _error.value = PandoraSession.describe(e)
                } finally {
                    _checking.value = false
                }
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    private fun refreshNowPlaying() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val meta: MediaMetadata = c.mediaMetadata
        _nowPlaying.value = NowPlaying(
            hasItem = item != null,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            artUri = meta.artworkUri,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            thumbsUp = (meta.userRating as? ThumbRating)?.isThumbsUp == true,
            stationToken = meta.extras?.getString(PlaybackService.EXTRA_STATION_TOKEN),
        )
    }

    fun clearError() {
        _error.value = null
    }

    // --- account ---

    fun login(email: String, password: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                PandoraSession.login(email, password)
                _error.value = null
            } catch (e: Exception) {
                _error.value = PandoraSession.describe(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun logout() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        PandoraSession.logout()
    }

    fun setQuality(q: AudioQuality) {
        PandoraSession.quality = q
        _quality.value = q
    }

    // --- stations ---

    fun refreshStations() {
        viewModelScope.launch {
            _busy.value = true
            try {
                PandoraSession.refreshStations()
            } catch (e: Exception) {
                _error.value = PandoraSession.describe(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun playStation(station: Station) {
        val c = controller ?: return
        c.setMediaItem(PlaybackService.stationRequest(station))
        c.prepare()
        c.play()
    }

    fun deleteStation(station: Station) {
        viewModelScope.launch {
            try {
                PandoraSession.call { deleteStation(station.token) }
                PandoraSession.refreshStations()
            } catch (e: Exception) {
                _error.value = PandoraSession.describe(e)
            }
        }
    }

    fun search(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            _searching.value = true
            try {
                _searchResults.value = PandoraSession.call { search(text.trim()) }
            } catch (e: Exception) {
                _error.value = PandoraSession.describe(e)
            } finally {
                _searching.value = false
            }
        }
    }

    fun createStation(result: SearchResult) {
        viewModelScope.launch {
            try {
                val station = PandoraSession.call { createStation(result.musicToken) }
                PandoraSession.refreshStations()
                playStation(station)
            } catch (e: Exception) {
                _error.value = PandoraSession.describe(e)
            }
        }
    }

    // --- transport ---

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    fun thumbUp() = send(PlaybackService.CMD_THUMB_UP)
    fun thumbDown() = send(PlaybackService.CMD_THUMB_DOWN)
    fun tired() = send(PlaybackService.CMD_TIRED)

    private fun send(action: String) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY)
    }
}
