package com.isaakrichards.troubadour.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ThumbRating
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.isaakrichards.troubadour.R
import com.isaakrichards.troubadour.data.PandoraSession
import com.isaakrichards.troubadour.pandora.AudioQuality
import com.isaakrichards.troubadour.pandora.Station
import com.isaakrichards.troubadour.pandora.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Plays Pandora stations through ExoPlayer and exposes them over a
 * MediaLibrarySession, which gives us the notification, lock screen,
 * headset buttons and Android Auto browsing for free.
 *
 * A station is started by setting a MediaItem whose id is "station:<token>";
 * the service swaps it for real tracks and keeps the queue topped up.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stationToken: String? = null
    private var refillJob: Job? = null
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        PandoraSession.init(this)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val sessionActivity = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background if music is on; otherwise go quietly.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // --- queue management ---

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            topUpIfLow()
            trimHistory()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) refill(advanceAfter = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            // Usually an expired stream URL (Pandora's links time out). Move along.
            Log.w(TAG, "Playback error, skipping track", error)
            consecutiveErrors++
            if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                report("Several songs failed to play. Press play to try again.")
                consecutiveErrors = 0
                return
            }
            skipToNext()
        }
    }

    private fun skipToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        } else {
            refill(advanceAfter = true)
        }
    }

    private fun topUpIfLow() {
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining < REFILL_THRESHOLD) refill()
    }

    private fun trimHistory() {
        val index = player.currentMediaItemIndex
        if (index > MAX_HISTORY) {
            scope.launch { player.removeMediaItems(0, index - KEEP_HISTORY) }
        }
    }

    private fun refill(advanceAfter: Boolean = false) {
        val token = stationToken ?: return
        if (refillJob?.isActive == true) return
        refillJob = scope.launch {
            try {
                val tracks = PandoraSession.call { getPlaylist(token) }
                if (token != stationToken) return@launch // station changed meanwhile
                val quality = PandoraSession.quality
                player.addMediaItems(tracks.mapNotNull { it.toMediaItem(quality) })
                if (advanceAfter && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch more songs", e)
                report(PandoraSession.describe(e))
            }
        }
    }

    private suspend fun startStation(token: String): MediaSession.MediaItemsWithStartPosition {
        refillJob?.cancel()
        stationToken = token
        consecutiveErrors = 0
        PandoraSession.lastStationToken = token
        val tracks = try {
            PandoraSession.call { getPlaylist(token) }
        } catch (e: Exception) {
            report(PandoraSession.describe(e))
            throw e
        }
        val quality = PandoraSession.quality
        return MediaSession.MediaItemsWithStartPosition(
            tracks.mapNotNull { it.toMediaItem(quality) }, 0, C.TIME_UNSET,
        )
    }

    // --- feedback ---

    private fun currentTokens(): Pair<String, String>? {
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return null
        val station = extras.getString(EXTRA_STATION_TOKEN) ?: return null
        val track = extras.getString(EXTRA_TRACK_TOKEN) ?: return null
        return station to track
    }

    private fun rateCurrent(positive: Boolean) {
        val (station, track) = currentTokens() ?: return
        if (positive) {
            val index = player.currentMediaItemIndex
            player.currentMediaItem?.let { item ->
                val rated = item.mediaMetadata.buildUpon().setUserRating(ThumbRating(true)).build()
                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(rated).build())
            }
        } else {
            skipToNext() // a banished song doesn't get to finish
        }
        scope.launch {
            try {
                PandoraSession.call { rate(station, track, positive) }
            } catch (e: Exception) {
                report(PandoraSession.describe(e))
            }
        }
    }

    private fun tiredOfCurrent() {
        val (_, track) = currentTokens() ?: return
        skipToNext()
        scope.launch {
            try {
                PandoraSession.call { sleepSong(track) }
            } catch (e: Exception) {
                report(PandoraSession.describe(e))
            }
        }
    }

    /** Errors reach the app through session extras (the id makes repeats fire again). */
    private fun report(message: String) {
        session?.setSessionExtras(bundleOf(EXTRA_ERROR to message, EXTRA_ERROR_ID to System.nanoTime()))
    }

    // --- session callback ---

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_THUMB_UP, Bundle.EMPTY))
                .add(SessionCommand(CMD_THUMB_DOWN, Bundle.EMPTY))
                .add(SessionCommand(CMD_TIRED, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_THUMB_UP -> rateCurrent(true)
                CMD_THUMB_DOWN -> rateCurrent(false)
                CMD_TIRED -> tiredOfCurrent()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_ID) return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            val station = PandoraSession.stations.value.firstOrNull { STATION_PREFIX + it.token == mediaId }
            return Futures.immediateFuture(
                if (station != null) LibraryResult.ofItem(station.toMediaItem(), null)
                else LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (parentId != ROOT_ID) {
                return@future LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
            try {
                val stations = PandoraSession.stations.value.ifEmpty { PandoraSession.refreshStations() }
                LibraryResult.ofItemList(stations.map { it.toMediaItem() }, params)
            } catch (e: Exception) {
                Log.w(TAG, "Could not list stations", e)
                LibraryResult.ofError<ImmutableList<MediaItem>>(LibraryResult.RESULT_ERROR_IO)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val id = mediaItems.firstOrNull()?.mediaId.orEmpty()
            if (!id.startsWith(STATION_PREFIX)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return scope.future { startStation(id.removePrefix(STATION_PREFIX)) }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val token = PandoraSession.lastStationToken
                ?: PandoraSession.refreshStations().firstOrNull()?.token
                ?: throw IllegalStateException("No station to resume")
            startStation(token)
        }
    }

    private fun customLayout(): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName(getString(R.string.thumb_down))
            .setIconResId(R.drawable.ic_thumb_down)
            .setSessionCommand(SessionCommand(CMD_THUMB_DOWN, Bundle.EMPTY))
            .build(),
        CommandButton.Builder()
            .setDisplayName(getString(R.string.thumb_up))
            .setIconResId(R.drawable.ic_thumb_up)
            .setSessionCommand(SessionCommand(CMD_THUMB_UP, Bundle.EMPTY))
            .build(),
        CommandButton.Builder()
            .setDisplayName(getString(R.string.tired))
            .setIconResId(R.drawable.ic_tired)
            .setSessionCommand(SessionCommand(CMD_TIRED, Bundle.EMPTY))
            .build(),
    )

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Stations")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build(),
        )
        .build()

    companion object {
        private const val TAG = "Troubadour"
        private const val REFILL_THRESHOLD = 2
        private const val MAX_HISTORY = 25
        private const val KEEP_HISTORY = 5
        private const val MAX_CONSECUTIVE_ERRORS = 3

        const val ROOT_ID = "root"
        const val STATION_PREFIX = "station:"
        const val TRACK_PREFIX = "track:"

        const val CMD_THUMB_UP = "troubadour.THUMB_UP"
        const val CMD_THUMB_DOWN = "troubadour.THUMB_DOWN"
        const val CMD_TIRED = "troubadour.TIRED"

        const val EXTRA_STATION_TOKEN = "stationToken"
        const val EXTRA_TRACK_TOKEN = "trackToken"
        const val EXTRA_ERROR = "error"
        const val EXTRA_ERROR_ID = "errorId"

        /** The item a controller sets to start a station. */
        fun stationRequest(station: Station): MediaItem = station.toMediaItem()

        fun Station.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(STATION_PREFIX + token)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setStation(name)
                    .setArtworkUri(artUrl?.toUri())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .build(),
            )
            .build()

        fun Track.toMediaItem(quality: AudioQuality): MediaItem? {
            val url = audioUrl(quality) ?: return null
            return MediaItem.Builder()
                .setMediaId(TRACK_PREFIX + trackToken)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(albumArtUrl?.toUri())
                        .setUserRating(if (rating == 1) ThumbRating(true) else ThumbRating())
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(
                            bundleOf(
                                EXTRA_STATION_TOKEN to stationToken,
                                EXTRA_TRACK_TOKEN to trackToken,
                            ),
                        )
                        .build(),
                )
                .build()
        }
    }
}
