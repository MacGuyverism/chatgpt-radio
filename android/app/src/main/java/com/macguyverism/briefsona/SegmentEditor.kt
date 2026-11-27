package com.macguyverism.briefsona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private fun durationText(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
internal fun Chevron() {
    Text("›", Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun TimeRemaining(budget: Airtime) {
    val message = when {
        budget.remaining < 0 -> "${durationText(-budget.remaining)} over target"
        budget.automaticCount == 0 -> "${durationText(budget.remaining)} remaining"
        else -> "${durationText(budget.remaining)} remaining for ${budget.automaticCount} automatic " +
            if (budget.automaticCount == 1) "segment" else "segments"
    }
    Text(message, color = if (budget.remaining < budget.automaticCount) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary)
}

@Composable
internal fun SegmentLineup(profile: StationProfile, openSegment: (StationSegment) -> Unit,
    edit: (StationProfile) -> Unit, saving: Boolean, saveLineup: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val budget = profile.airtime()
    val centers = remember { mutableMapOf<String, Float>() }
    var orderMenu by remember { mutableStateOf(false) }
    Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${profile.minutes} minutes · ${profile.segments.size} segments", Modifier.weight(1f))
        Text("${profile.segments.count { it.enabled }} enabled", color = green)
    }
    TimeRemaining(budget)
    Box {
        Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(8.dp))
            .clickable(enabled = !saving) { orderMenu = true }.padding(14.dp)) {
            Text("Playback order", Modifier.weight(1f))
            Text(if (profile.random) "Random ⌄" else "Fixed ⌄", color = colors.primary)
        }
        DropdownMenu(orderMenu, { orderMenu = false }) {
            for (random in listOf(false, true)) {
                DropdownMenuItem(text = { Text(if (random) "Random" else "Fixed") }, onClick = {
                    edit(profile.copy(random = random))
                    orderMenu = false
                })
            }
        }
    }
    profile.segments.forEachIndexed { index, segment ->
        key(segment.id) {
            val custom = index in 1 until profile.segments.lastIndex
            var menu by remember { mutableStateOf(false) }
            var dragging by remember { mutableStateOf(false) }
            fun move(to: Int) {
                if (custom && to in 1 until profile.segments.lastIndex && index != to) {
                    edit(profile.copy(segments = profile.segments.toMutableList().apply { add(to, removeAt(index)) }))
                }
                menu = false
            }
            Card(onClick = { openSegment(segment) }, enabled = !saving, modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { centers[segment.id] = it.positionInRoot().y + it.size.height / 2f },
                colors = CardDefaults.cardColors(containerColor = if (dragging) colors.primary.copy(alpha = .2f) else colors.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (custom) {
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !saving, modifier = Modifier
                                .semantics { contentDescription = "Reorder or remove ${segment.name}" }
                                .pointerInput(profile.segments, saving) {
                                    if (saving) return@pointerInput
                                    var offset = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = true; offset = 0f },
                                        onDragCancel = { dragging = false },
                                        onDragEnd = {
                                            dragging = false
                                            val target = (centers[segment.id] ?: 0f) + offset
                                            (1 until profile.segments.lastIndex).minByOrNull { i ->
                                                abs((centers[profile.segments[i].id] ?: Float.POSITIVE_INFINITY) - target)
                                            }?.let(::move)
                                        },
                                        onDrag = { change, delta -> change.consume(); offset += delta.y }
                                    )
                                }) { Text("⠿") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 1, onClick = { move(index - 1) })
                                DropdownMenuItem(text = { Text("Move down") }, enabled = index < profile.segments.lastIndex - 1,
                                    onClick = { move(index + 1) })
                                DropdownMenuItem(text = { Text("Remove") }, onClick = {
                                    menu = false
                                    edit(profile.copy(segments = profile.segments - segment))
                                })
                            }
                        }
                    } else {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("🔒") }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(segment.name, style = MaterialTheme.typography.titleMedium)
                        val estimate = budget.estimates[segment.id]
                        Text(if (estimate == null) "Disabled" else
                            "${durationText(estimate)} · ${if (!custom) "Pinned" else if (segment.durationSeconds == null) "Automatic" else "Set time"}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (segment.enabled) Text("✓", color = green)
                    Chevron()
                }
            }
        }
    }
    OutlinedButton(onClick = { openSegment(StationSegment(name = "")) }, modifier = Modifier.fillMaxWidth(),
        enabled = !saving && profile.canAddSegment) { Text("+ Add segment") }
    Text("Hold and drag the handle to reorder, or tap it for options. Opening and Closing are pinned.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    Button(onClick = saveLineup, modifier = Modifier.fillMaxWidth(), enabled = !saving) { Text("Save changes") }
}

@Composable
internal fun EditSegment(segment: StationSegment, profile: StationProfile, pinned: Boolean, saving: Boolean,
    openSoundBites: (() -> Unit)?, update: (StationSegment) -> Unit, onSave: () -> Unit) {
    val budget = profile.airtime()
    val estimate = budget.estimates[segment.id] ?: 0
    val fixed = profile.fixedSeconds(segment)
    val automatic = fixed == null
    Panel {
        OutlinedTextField(segment.name, { update(segment.copy(name = it)) }, enabled = !saving,
            label = { Text("Segment name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (pinned) "Always enabled" else "Enabled", Modifier.weight(1f))
            Switch(checked = segment.enabled, enabled = !pinned && !saving,
                onCheckedChange = { update(segment.copy(enabled = it)) })
        }
        HorizontalDivider()
        TextButton(onClick = {}, enabled = false) { Text("Reporter") }
        OutlinedTextField(segment.instructions, { update(segment.copy(instructions = it)) }, enabled = !saving,
            label = { Text("Content instructions") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Text("Approximate time", style = MaterialTheme.typography.titleMedium)
        if (!pinned) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(automatic, { update(segment.copy(durationSeconds = null)) },
                    enabled = !saving, label = { Text("Automatic") })
                FilterChip(!automatic, { if (automatic) update(segment.copy(durationSeconds = estimate.coerceAtLeast(1))) },
                    enabled = !saving, label = { Text("Set time") })
            }
        }
        if (fixed != null) {
            var minutes by rememberSaveable(segment.id) { mutableStateOf((fixed / 60).toString()) }
            var seconds by rememberSaveable(segment.id) { mutableStateOf((fixed % 60).toString().padStart(2, '0')) }
            fun change(m: String, s: String) {
                minutes = m
                seconds = s
                val total = (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
                update(segment.copy(durationSeconds = if ((s.toIntOrNull() ?: 0) < 60) total else 0))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minutes, { if (it.length <= 2 && it.all(Char::isDigit)) change(it, seconds) },
                    label = { Text("Minutes") }, enabled = !saving, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(seconds, { if (it.length <= 2 && it.all(Char::isDigit)) change(minutes, it) },
                    label = { Text("Seconds") }, enabled = !saving, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (fixed !in 1..3600) Text("Use 0–59 seconds and a total time from 0:01 to 60:00.",
                color = MaterialTheme.colorScheme.error)
        } else if (segment.enabled) {
            Text("Estimated time · ${durationText(estimate)}")
        }
        TimeRemaining(budget)
        HorizontalDivider()
        TextButton(onClick = {}, enabled = false) { Text("Preferred sources") }
        OutlinedButton(onClick = { openSoundBites?.invoke() }, enabled = !saving && openSoundBites != null,
            modifier = Modifier.fillMaxWidth()) {
            val cue = segment.cue
            Text(when {
                cue == null -> "Sound bite"
                cue.startsWith("file:") -> "Sound bite · Imported audio"
                else -> "Sound bite · ${bundledCueTitle(cue.removePrefix("bundled:"))}"
            })
        }
    }
    Button(onClick = onSave, enabled = !saving && segment.name.isNotBlank() && (fixed == null || fixed in 1..3600)
        && budget.remaining >= budget.automaticCount, modifier = Modifier.fillMaxWidth()) { Text("Save segment") }
}
