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
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

@Composable
internal fun StationPages(scroll: ScrollState, records: List<StationRecord>, morning: StationProfile,
    playable: Boolean, status: String, retrievePublished: (() -> Unit)?, loadStation: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { StationDatabase.get(context) }
    val scope = rememberCoroutineScope()
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var lineupJson by rememberSaveable { mutableStateOf<String?>(null) }
    var segmentJson by rememberSaveable { mutableStateOf<String?>(null) }
    var soundBitesOpen by rememberSaveable { mutableStateOf(false) }
    var cueDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val segment = remember(segmentJson) { segmentJson?.let { StationSegment.from(JSONObject(it)) } }
    var scheduleMode by rememberSaveable { mutableStateOf<StationMode?>(null) }
    val editingSchedule = scheduleMode != null
    val editingSegments = lineupJson != null
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draft = remember(draftJson) { draftJson?.let { StationProfile.from(JSONObject(it)) } }
    LaunchedEffect(draft?.id, editingSegments, segment?.id, editingSchedule, soundBitesOpen) { scroll.scrollTo(0) }

    fun edit(profile: StationProfile) {
        draftJson = profile.json().toString()
        error = null
    }

    fun goBack() {
        error = null
        if (soundBitesOpen) {
            soundBitesOpen = false
            cueDraft = null
        } else if (segmentJson != null) {
            segmentJson = null
        } else if (editingSchedule) {
            scheduleMode = null
        } else if (editingSegments) {
            lineupJson = null
        } else {
            draftJson = null
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
        Box(Modifier.fillMaxWidth()) {
            Text("STATIONS", Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { edit(StationProfile()) }, modifier = Modifier.align(Alignment.CenterEnd)
                .semantics { contentDescription = "Add station" }) { Text("+") }
        }
        val profiles = listOf(morning) + records.filter { it.id != morning.id }.map { it.decode() }
        profiles.forEach { profile ->
            val loaded = profile.id == morning.id
            OutlinedCard(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.icon)
                    Text(profile.name, style = MaterialTheme.typography.titleMedium,
                        color = if (loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text("${profile.mode.label} · ${profile.minutes} min · ${profile.segments.size} segments")
                    records.firstOrNull { it.id == profile.id }?.let { Text(it.backup) }
                    if (loaded) {
                        Text("Loaded in Radio")
                        OutlinedButton(onClick = loadStation, enabled = playable) { Text("Load station") }
                    }
                }
            }
        }
        content()
        return
    }

    val heading = when {
        soundBitesOpen -> "Sound bites"
        segment != null -> "Edit Segment"
        editingSchedule -> "Schedule"
        editingSegments -> "Segments"
        else -> "Station Profile"
    }
    TextButton(onClick = ::goBack, enabled = !saving) {
        Text(when {
            soundBitesOpen -> "Back to Edit Segment"
            segment != null -> "Back to Segments"
            editingSchedule || editingSegments -> "Back to Station Profile"
            else -> "Back to Stations"
        })
    }
    Text(heading, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.headlineSmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val mode = scheduleMode
    if (mode != null) {
        val isBroadcastStation = draft.id == morning.id
        StationSchedule(draft.name, mode, saving, if (isBroadcastStation) status else "",
            if (isBroadcastStation) retrievePublished else null, onMode = {
                scheduleMode = it
                error = null
            }, onSave = {
                saving = true
                scope.launch {
                    try {
                        dao.saveSchedule(draft, mode)
                        edit(draft.copy(mode = mode))
                        scheduleMode = null
                        StationBackup.request(context)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message
                    } catch (_: Exception) {
                        error = "Could not save the schedule. Try again."
                    } finally {
                        saving = false
                    }
                }
            })
        return
    }
    if (editingSegments) {
        val lineup = remember(lineupJson) { StationProfile.from(JSONObject(requireNotNull(lineupJson))) }
        if (segment != null && soundBitesOpen) {
            SoundBites(segment.name, cueDraft, saving, update = { cueDraft = it }, onSave = {
                val cue = cueDraft
                saving = true
                scope.launch {
                    try {
                        val committed = dao.saveSoundBite(draft, segment.id, cue)
                        edit(draft.copy(segments = committed.segments))
                        lineupJson = lineup.withSegment(lineup.segments.first { it.id == segment.id }.copy(cue = cue))
                            .json().toString()
                        segmentJson = segment.copy(cue = cue).json().toString()
                        soundBitesOpen = false
                        cueDraft = null
                        StationBackup.request(context)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        error = "Could not save the sound bite. Try again."
                    } finally {
                        saving = false
                    }
                }
            })
            return
        }
        if (segment != null) {
            val pinned = segment.id == lineup.segments.first().id || segment.id == lineup.segments.last().id
            val openSoundBites: (() -> Unit)? = if (draft.segments.any { it.id == segment.id }) {
                {
                    cueDraft = segment.cue
                    soundBitesOpen = true
                    error = null
                }
            } else null
            EditSegment(segment, pinned, saving, openSoundBites, update = {
                if (!saving) {
                    segmentJson = it.json().toString()
                    error = null
                }
            }, onSave = {
                saving = true
                scope.launch {
                    try {
                        val committed = dao.saveSegment(draft, segment)
                        edit(draft.copy(segments = committed.segments))
                        lineupJson = lineup.withSegment(segment).json().toString()
                        segmentJson = null
                        StationBackup.request(context)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message
                    } catch (_: Exception) {
                        error = "Could not save the segment. Try again."
                    } finally {
                        saving = false
                    }
                }
            })
            return
        }
        SegmentLineup(lineup, openSegment = {
            error = null
            segmentJson = it.json().toString()
        }, edit = {
            if (!saving) {
                lineupJson = it.json().toString()
                error = null
            }
        }, saving = saving, saveLineup = {
            saving = true
            scope.launch {
                try {
                    dao.saveLineup(lineup)
                    StationBackup.request(context)
                    edit(draft.copy(random = lineup.random, segments = lineup.segments))
                    lineupJson = null
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: IllegalArgumentException) {
                    error = lineup.validationError() ?: "Save the station duration before saving this lineup."
                } catch (_: Exception) {
                    error = "Could not save the lineup. Try again."
                } finally {
                    saving = false
                }
            }
        })
        return
    }

    OutlinedTextField(draft.name, { edit(draft.copy(name = it)) }, label = { Text("Station name") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
    OutlinedTextField(draft.icon, { edit(draft.copy(icon = it)) }, label = { Text("Station icon") },
        singleLine = true, enabled = !saving)
    OutlinedTextField(draft.instructions, { edit(draft.copy(instructions = it)) },
        label = { Text("Station-wide broadcast instructions") }, modifier = Modifier.fillMaxWidth(), enabled = !saving)
    OutlinedTextField(draft.minutes.takeIf { it > 0 }?.toString().orEmpty(), {
        edit(draft.copy(minutes = it.toIntOrNull() ?: 0))
    }, label = { Text("Target duration (minutes)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(), enabled = !saving)
    OutlinedButton(onClick = { scheduleMode = draft.mode; error = null },
        modifier = Modifier.fillMaxWidth(), enabled = !saving) {
        Text("Schedule · ${draft.mode.label}")
    }
    OutlinedButton(onClick = { lineupJson = draft.json().toString() }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
        Text("Segments · ${draft.segments.size}")
    }
    Button(onClick = { save(draft) }, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
        Text("Save station")
    }
    if (draft.id == morning.id || records.any { it.id == draft.id }) {
        OutlinedButton(onClick = { save(draft.duplicate(), copy = true) }, enabled = !saving) { Text("Create a copy") }
    }
    listOf("Reporter library", "Produce broadcast now", "Delete station").forEach { label ->
        OutlinedButton(onClick = {}, enabled = false) { Text(label) }
    }
}

@Composable
private fun StationSchedule(name: String, mode: StationMode, saving: Boolean, status: String,
    retrievePublished: (() -> Unit)?, onMode: (StationMode) -> Unit, onSave: () -> Unit) {
    if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == StationMode.MANUAL, { onMode(StationMode.MANUAL) },
            label = { Text("Manual") }, enabled = !saving)
        FilterChip(mode != StationMode.MANUAL, {
            if (mode == StationMode.MANUAL) onMode(StationMode.SCHEDULED)
        }, label = { Text("Automatic") }, enabled = !saving)
    }
    if (mode != StationMode.MANUAL) {
        Text(mode.label)
        val paused = mode == StationMode.PAUSED
        OutlinedButton(onClick = { onMode(if (paused) StationMode.SCHEDULED else StationMode.PAUSED) },
            enabled = !saving) { Text(if (paused) "Resume schedule" else "Pause schedule") }
    }
    listOf("Broadcast days", "Release time", "Time zone").forEach { label ->
        OutlinedButton(onClick = {}, enabled = false) { Text(label) }
    }
    if (mode == StationMode.SCHEDULED && status.isNotBlank()) {
        val json = remember(status) { runCatching { JSONObject(status) }.getOrNull() }
        val now = Instant.now()
        val next = listOf("", "next_").firstOrNull { prefix ->
            runCatching { Instant.parse(json?.optString("${prefix}release_at")).isAfter(now) }.getOrDefault(false)
        }
        if (next != null && json != null) {
            Text("Next broadcast · " + localTime(json.optString("${next}release_at")))
            val start = localTime(json.optString("${next}production_at"))
            if (start.isNotEmpty()) Text("Production start · $start")
        }
    }
    if (mode == StationMode.MANUAL) {
        OutlinedButton(onClick = {}, enabled = false) { Text("Produce broadcast now") }
        OutlinedButton(onClick = {}, enabled = false) { Text("Retry production") }
    }
    OutlinedButton(onClick = { retrievePublished?.invoke() }, enabled = retrievePublished != null && !saving) {
        Text("Retrieve existing broadcast")
    }
    Button(onClick = onSave, enabled = !saving) { Text("Save schedule") }
}

@Composable
private fun SegmentLineup(profile: StationProfile, openSegment: (StationSegment) -> Unit,
    edit: (StationProfile) -> Unit, saving: Boolean, saveLineup: () -> Unit) {
    Text("${profile.segments.count { it.enabled }} of ${profile.segments.size} enabled")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!profile.random, { edit(profile.copy(random = false)) }, enabled = !saving, label = { Text("Fixed order") })
        FilterChip(profile.random, { edit(profile.copy(random = true)) }, enabled = !saving, label = { Text("Random order") })
    }
    profile.segments.forEachIndexed { index, segment ->
        val custom = index in 1 until profile.segments.lastIndex
        fun move(destination: Int) {
            edit(profile.copy(segments = profile.segments.toMutableList().apply { add(destination, removeAt(index)) }))
        }
        OutlinedCard(onClick = { openSegment(segment) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(segment.name, style = MaterialTheme.typography.titleMedium)
                val role = when (index) {
                    0 -> "Opening · pinned"
                    profile.segments.lastIndex -> "Closing · pinned"
                    else -> if (segment.enabled) "Enabled" else "Disabled"
                }
                Text("$role · ${segment.weight}×")
                if (custom) {
                    Row {
                        TextButton(onClick = { move(index - 1) }, enabled = !saving && index > 1) { Text("Move up") }
                        TextButton(onClick = { move(index + 1) },
                            enabled = !saving && index < profile.segments.lastIndex - 1) { Text("Move down") }
                        TextButton(onClick = { edit(profile.copy(segments = profile.segments - segment)) },
                            enabled = !saving) { Text("Remove") }
                    }
                }
            }
        }
    }
    Button(onClick = { openSegment(StationSegment(name = "")) },
        enabled = !saving && profile.segments.size - 2 < (profile.minutes / 3).coerceAtMost(20)) { Text("+ Add Segment") }
    Button(onClick = saveLineup, enabled = !saving) { Text("Save lineup") }
}

@Composable
private fun EditSegment(segment: StationSegment, pinned: Boolean, saving: Boolean, openSoundBites: (() -> Unit)?,
    update: (StationSegment) -> Unit, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (pinned) "Always enabled" else "Enabled", Modifier.weight(1f))
        Switch(checked = segment.enabled, enabled = !pinned && !saving,
            onCheckedChange = { update(segment.copy(enabled = it)) })
    }
    OutlinedTextField(segment.instructions, { update(segment.copy(instructions = it)) },
        label = { Text("Content instructions") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(segment.name, { update(segment.copy(name = it)) },
        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    var weightText by rememberSaveable(segment.id) { mutableStateOf(segment.weight.toString()) }
    OutlinedTextField(weightText, {
        weightText = it
        val parsed = it.replace(',', '.').toDoubleOrNull()
        update(segment.copy(weight = parsed?.takeIf { value -> value.isFinite() } ?: 0.0))
    }, label = { Text("Relative airtime (×)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { openSoundBites?.invoke() }, enabled = !saving && openSoundBites != null) {
        Text("Sound bites")
    }
    OutlinedButton(onClick = {}, enabled = false) { Text("Reporter") }
    OutlinedButton(onClick = {}, enabled = false) { Text("Preferred sources") }
    Button(onClick = onSave, enabled = !saving && segment.name.isNotBlank() && segment.weight > 0) {
        Text("Save segment")
    }
}

@Composable
private fun SoundBites(segmentName: String, cue: String?, saving: Boolean,
    update: (String?) -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestUpdate by rememberUpdatedState(update)
    var importing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var bundled by remember { mutableStateOf(false) }
    val preview = remember { CuePreview() }
    fun choose(value: String?) {
        preview.stop()
        playing = false
        feedback = null
        latestUpdate(value)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            feedback = null
            scope.launch {
                try {
                    choose(importStationCue(context, uri))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    feedback = "Could not import this clip. Choose a supported audio file under 45 MB."
                } finally {
                    importing = false
                }
            }
        }
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    Text(segmentName, style = MaterialTheme.typography.titleMedium)
    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text(when {
        cue == null -> "No sound bite"
        cue.startsWith("file:") -> "Imported sound bite"
        else -> bundledCueTitle(cue.removePrefix("bundled:"))
    })
    val available = !saving && !importing
    Box {
        OutlinedButton(onClick = { bundled = true }, enabled = available) { Text("Bundled library") }
        DropdownMenu(bundled, { bundled = false }) {
            bundledCueGroups.forEach { (group, cues) ->
                DropdownMenuItem(text = { Text(group) }, onClick = {}, enabled = false)
                cues.forEach { id ->
                    DropdownMenuItem(text = { Text(bundledCueTitle(id)) }, onClick = {
                        bundled = false
                        choose("bundled:$id")
                    })
                }
            }
        }
    }
    Text("My uploads", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = available) {
        Text(if (cue?.startsWith("file:") == true) "Replace imported audio" else "Import audio")
    }
    if (cue != null) {
        Row {
            TextButton(onClick = {
                feedback = null
                preview.stop()
                playing = !playing
                if (playing) preview.play({
                    if (cue.startsWith("file:")) {
                        setDataSource(importedCueFile(context, cue).absolutePath)
                    } else {
                        context.assets.openFd("station-cues/${cue.removePrefix("bundled:")}.mp3").use {
                            setDataSource(it.fileDescriptor, it.startOffset, it.length)
                        }
                    }
                }, finished = { playing = false }, failed = {
                    playing = false
                    feedback = "Could not play this sound bite."
                })
            }, enabled = available) { Text(if (playing) "Stop preview" else "Preview") }
            TextButton(onClick = { choose(null) }, enabled = available) { Text("Detach") }
        }
    }
    Button(onClick = onSave, enabled = available) { Text("Save sound bite") }
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
