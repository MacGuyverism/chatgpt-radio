package com.macguyverism.briefsona

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun StationPages(scroll: ScrollState, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StationDatabase.get(context) }
    val records by dao.observe().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSegments by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draft = remember(draftJson) { draftJson?.let { StationProfile.from(JSONObject(it)) } }
    LaunchedEffect(draft?.id, editingSegments) { scroll.scrollTo(0) }

    fun edit(profile: StationProfile) {
        draftJson = profile.json().toString()
        error = null
    }

    fun goBack() {
        if (editingSegments) {
            editingSegments = false
        } else {
            draftJson = null
            error = null
        }
    }

    fun save(profile: StationProfile, copy: Boolean = false) {
        error = profile.validationError()
        if (error != null || saving) return
        saving = true
        scope.launch {
            try {
                dao.save(profile.copy(name = profile.name.trim()))
                StationBackup.request(context)
                if (copy) edit(profile) else goBack()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                error = "Could not save the station. Try again."
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = draft != null) { if (!saving) goBack() }
    if (draft == null) {
        Button(onClick = { edit(StationProfile()) }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add Station")
        }
        content()
        records.forEach { record ->
            key(record.id) {
                val profile = remember(record.profile) { record.decode() }
                OutlinedButton(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${profile.mode.label} · ${profile.minutes} min · ${record.backup}")
                    }
                }
            }
        }
        return
    }

    TextButton(onClick = ::goBack, enabled = !saving) {
        Text(if (editingSegments) "Back to Station Profile" else "Back to Stations")
    }
    Text(if (editingSegments) "Segments" else "Station Profile", style = MaterialTheme.typography.headlineSmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (editingSegments) {
        SegmentEditor(draft, ::edit)
        return
    }

    OutlinedTextField(draft.name, { edit(draft.copy(name = it)) }, label = { Text("Station name") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
    OutlinedTextField(draft.minutes.takeIf { it > 0 }?.toString().orEmpty(), {
        edit(draft.copy(minutes = it.toIntOrNull() ?: 0))
    }, label = { Text("Target duration (minutes)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(), enabled = !saving)
    Text("Production mode", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(draft.mode == StationMode.MANUAL, { edit(draft.copy(mode = StationMode.MANUAL)) },
            label = { Text("Manual") }, enabled = !saving)
        FilterChip(draft.mode != StationMode.MANUAL, {
            if (draft.mode == StationMode.MANUAL) edit(draft.copy(mode = StationMode.SCHEDULED))
        }, label = { Text("Scheduled") }, enabled = !saving)
    }
    if (draft.mode != StationMode.MANUAL) {
        if (draft.mode == StationMode.PAUSED) Text(draft.mode.label)
        OutlinedButton(onClick = {
            edit(draft.copy(mode = if (draft.mode == StationMode.PAUSED) StationMode.SCHEDULED else StationMode.PAUSED))
        }, enabled = !saving) {
            Text(if (draft.mode == StationMode.PAUSED) "Resume schedule" else "Pause schedule")
        }
    }
    OutlinedButton(onClick = { editingSegments = true }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
        Text("Segments · ${draft.segments.size}")
    }
    Button(onClick = { save(draft) }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
        Text("Save station")
    }
    if (records.any { it.id == draft.id }) {
        OutlinedButton(onClick = { save(draft.duplicate(), copy = true) }, enabled = !saving) { Text("Copy station") }
    }
}

@Composable
private fun SegmentEditor(profile: StationProfile, edit: (StationProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestProfile by rememberUpdatedState(profile)
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var playingCue by remember { mutableStateOf<String?>(null) }
    val preview = remember { CuePreview() }

    fun stopPreview(segmentId: String) {
        if (playingCue == segmentId) {
            preview.stop()
            playingCue = null
        }
    }

    fun applyCue(segmentId: String, cue: String?) {
        stopPreview(segmentId)
        val current = latestProfile
        edit(current.copy(segments = current.segments.map {
            if (it.id == segmentId) it.copy(cue = cue) else it
        }))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val segmentId = target
        target = null
        if (uri != null && segmentId != null) {
            importing = true
            feedback = null
            scope.launch {
                try {
                    applyCue(segmentId, importStationCue(context, uri))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    feedback = "Could not import this clip. Choose a supported audio file under 45 MB."
                } finally {
                    importing = false
                }
            }
        }
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!profile.random, { edit(profile.copy(random = false)) }, label = { Text("Fixed order") })
        FilterChip(profile.random, { edit(profile.copy(random = true)) }, label = { Text("Random order") })
    }
    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    profile.segments.forEachIndexed { index, segment ->
        key(segment.id) {
            val custom = index in 1 until profile.segments.lastIndex
            fun update(replacement: StationSegment) {
                edit(profile.copy(segments = profile.segments.toMutableList().apply { set(index, replacement) }))
            }
            fun move(destination: Int) {
                edit(profile.copy(segments = profile.segments.toMutableList().apply {
                    add(destination, removeAt(index))
                }))
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (index) {
                        0 -> "Opening"
                        profile.segments.lastIndex -> "Closing"
                        else -> "Segment"
                    }, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(segment.name, { update(segment.copy(name = it)) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(segment.weight.takeIf { it > 0 }?.toString().orEmpty(), {
                        update(segment.copy(weight = it.toIntOrNull() ?: 0))
                    }, label = { Text("Duration weight") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    if (custom) {
                        Row {
                            TextButton(onClick = { move(index - 1) }, enabled = index > 1) { Text("Move up") }
                            TextButton(onClick = { move(index + 1) },
                                enabled = index < profile.segments.lastIndex - 1) { Text("Move down") }
                            TextButton(onClick = {
                                stopPreview(segment.id)
                                edit(profile.copy(segments = profile.segments - segment))
                            }, enabled = !importing) { Text("Remove") }
                        }
                    }
                    val cue = segment.cue
                    Text(when {
                        cue == null -> "No sound bite"
                        cue.startsWith("file:") -> "Imported sound bite"
                        else -> cue.removePrefix("bundled:").replaceFirstChar { it.uppercase() }
                    })
                    var bundled by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { bundled = true }, enabled = !importing) { Text("Choose bundled cue") }
                        DropdownMenu(bundled, { bundled = false }) {
                            bundledCues.forEach { name ->
                                DropdownMenuItem(text = { Text(name.replaceFirstChar { it.uppercase() }) }, onClick = {
                                    bundled = false
                                    applyCue(segment.id, "bundled:$name")
                                })
                            }
                        }
                    }
                    TextButton(onClick = { target = segment.id; picker.launch(arrayOf("audio/*")) },
                        enabled = !importing) { Text("Import audio") }
                    if (cue != null) {
                        Row {
                            TextButton(onClick = {
                                feedback = null
                                preview.stop()
                                if (playingCue == segment.id) {
                                    playingCue = null
                                } else {
                                    playingCue = segment.id
                                    preview.play({
                                        if (cue.startsWith("file:")) {
                                            setDataSource(importedCueFile(context, cue).absolutePath)
                                        } else {
                                            context.assets.openFd("station-cues/${cue.removePrefix("bundled:")}.mp3").use {
                                                setDataSource(it.fileDescriptor, it.startOffset, it.length)
                                            }
                                        }
                                    }, finished = { playingCue = null }, failed = {
                                        playingCue = null
                                        feedback = "Could not play this sound bite."
                                    })
                                }
                            }) { Text(if (playingCue == segment.id) "Stop preview" else "Preview") }
                            TextButton(onClick = { applyCue(segment.id, null) }, enabled = !importing) { Text("Detach") }
                        }
                    }
                }
            }
        }
    }
    Button(onClick = {
        edit(profile.copy(segments = profile.segments.toMutableList().apply {
            add(lastIndex, StationSegment(name = ""))
        }))
    }, enabled = profile.segments.size - 2 < profile.minutes / 3) { Text("+ Add Segment") }
}

private class CuePreview {
    private var player: MediaPlayer? = null

    fun stop() {
        player?.release()
        player = null
    }

    fun play(source: MediaPlayer.() -> Unit, finished: () -> Unit, failed: () -> Unit) {
        val media = MediaPlayer()
        player = media
        try {
            media.source()
            media.setOnCompletionListener {
                stop()
                finished()
            }
            media.setOnErrorListener { _, _, _ ->
                stop()
                failed()
                true
            }
            media.setOnPreparedListener { it.start() }
            media.prepareAsync()
        } catch (_: Exception) {
            stop()
            failed()
        }
    }
}
