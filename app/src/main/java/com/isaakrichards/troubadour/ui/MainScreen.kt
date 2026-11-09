package com.isaakrichards.troubadour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.isaakrichards.troubadour.R
import com.isaakrichards.troubadour.pandora.AudioQuality
import com.isaakrichards.troubadour.pandora.Station
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showQuality by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Station?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        vm.clearError()
        snackbar.showSnackbar(message)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Troubadour", fontFamily = FontFamily.Serif) },
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "New station")
                        }
                        IconButton(onClick = vm::refreshStations) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh stations")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Audio quality") },
                                    onClick = { menuOpen = false; showQuality = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    onClick = { menuOpen = false; vm.logout() },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (np.hasItem) NowPlayingBar(np, vm, onExpand = { showPlayer = true })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (stations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator()
                    else Text(
                        "No stations yet.\nTap the search icon to start one.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    items(stations, key = { it.token }) { station ->
                        StationRow(
                            station = station,
                            isCurrent = station.token == np.stationToken,
                            onClick = { vm.playStation(station) },
                            onLongClick = { if (!station.isQuickMix) pendingDelete = station },
                        )
                    }
                }
            }
        }

        if (showPlayer && np.hasItem) {
            BackHandler { showPlayer = false }
            NowPlayingScreen(
                np = np,
                stationName = stations.firstOrNull { it.token == np.stationToken }?.name,
                vm = vm,
                onClose = { showPlayer = false },
            )
        }
    }

    if (showSearch) NewStationDialog(vm, onDismiss = { showSearch = false })

    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("Audio quality") },
            text = {
                Column {
                    AudioQuality.entries.forEach { q ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = q == quality, onClick = { vm.setQuality(q) })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == quality, onClick = null)
                            Text(q.label, Modifier.padding(start = 12.dp))
                        }
                    }
                    Text(
                        "Applies to songs fetched from now on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showQuality = false }) { Text("Done") } },
        )
    }

    pendingDelete?.let { station ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete station?") },
            text = { Text("\"${station.name}\" will be removed from your Pandora account.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteStation(station); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(
    station: Station,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (station.isQuickMix) {
            { Text("Shuffle of your stations") }
        } else null,
        leadingContent = { Art(station.artUrl, Modifier.size(48.dp)) },
        trailingContent = if (isCurrent) {
            {
                Icon(
                    painterResource(R.drawable.ic_music_note),
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else Color.Transparent,
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun NowPlayingBar(np: NowPlaying, vm: MainViewModel, onExpand: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(np.artUri, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    np.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    np.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = vm::thumbDown) {
                Icon(painterResource(R.drawable.ic_thumb_down), contentDescription = "Thumbs down")
            }
            PlayPauseButton(np, vm)
            IconButton(onClick = vm::next) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next song")
            }
            ThumbUpButton(np, vm)
        }
    }
}

@Composable
private fun NowPlayingScreen(np: NowPlaying, stationName: String?, vm: MainViewModel, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close player")
                }
                Text(
                    stationName.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.weight(1f))
            Art(np.artUri, Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f))
            Spacer(Modifier.height(28.dp))
            Text(
                np.title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                np.artist, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                np.album, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::thumbDown) {
                    Icon(painterResource(R.drawable.ic_thumb_down), contentDescription = "Thumbs down")
                }
                IconButton(onClick = vm::tired) {
                    Icon(painterResource(R.drawable.ic_tired), contentDescription = "Tired of this song")
                }
                FilledIconButton(onClick = vm::playPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        painterResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (np.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = vm::next) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next song")
                }
                ThumbUpButton(np, vm)
            }
        }
    }
}

@Composable
private fun PlayPauseButton(np: NowPlaying, vm: MainViewModel) {
    IconButton(onClick = vm::playPause) {
        if (np.isBuffering && !np.isPlaying) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painterResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (np.isPlaying) "Pause" else "Play",
            )
        }
    }
}

@Composable
private fun ThumbUpButton(np: NowPlaying, vm: MainViewModel) {
    IconButton(onClick = vm::thumbUp) {
        Icon(
            painterResource(R.drawable.ic_thumb_up),
            contentDescription = if (np.thumbsUp) "Thumbed up" else "Thumbs up",
            tint = if (np.thumbsUp) MaterialTheme.colorScheme.primary
            else LocalContentColor.current.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Art(model: Any?, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NewStationDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    LaunchedEffect(query) {
        delay(350) // wait for the typing to settle
        vm.search(query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New station") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Artist or song") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(results) { result ->
                        ListItem(
                            headlineContent = { Text(result.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(result.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                vm.createStation(result)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
