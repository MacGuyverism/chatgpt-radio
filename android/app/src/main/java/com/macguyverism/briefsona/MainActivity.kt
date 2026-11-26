package com.macguyverism.briefsona

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executor

private val backgrounds = listOf(Color(0xff151517), Color(0xff141c26), Color(0xff211a16))
private val cards = listOf(Color(0xff262628), Color(0xff242f3c), Color(0xff332820))
private val accents = listOf(Color(0xffffb340), Color(0xff55caff), Color(0xffff4949),
    Color(0xff51e69a), Color(0xffb58aff), Color(0xffeeeeee))
private val green = Color(0xff51e69a)
private val blue = Color(0xff55caff)
private val muted = Color(0xffb6b5b5)
private val shape = RoundedCornerShape(14.dp)
private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Briefsona() }
    }
}

@Composable
private fun Briefsona() {
    val context = LocalContext.current
    val prefs by context.settings.data.collectAsState(emptyPreferences())
    val library = remember { Library.get(context).episodes() }
    val items by library.observe().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val save: (suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) -> Unit = { change ->
        scope.launch { context.settings.edit(change) }
    }
    val background = (prefs[Prefs.background] ?: 0).coerceIn(backgrounds.indices)
    val accent = accents[(prefs[Prefs.accent] ?: 0).coerceIn(accents.indices)]
    val wifi = prefs[Prefs.wifi] ?: false
    val endpoint = prefs[Prefs.endpoint] ?: BuildConfig.PROJECT_ORIGIN.takeIf { it.isNotBlank() }
    val status = prefs[Prefs.status].orEmpty()
    var screen by rememberSaveable { mutableStateOf("Radio") }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var liveId by remember { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val visible = items.filter { (it.remote || it.local != null) && it.date >= today.minusDays(6).toString() }
    val active = items.firstOrNull { it.id == liveId }
    val selected = visible.firstOrNull { it.id == prefs[Prefs.selected] }
    val chosen = if (playing) active ?: selected ?: visible.firstOrNull() else selected ?: active ?: visible.firstOrNull()
    val segmentList = remember(chosen?.chapters) { runCatching { chapters(chosen?.chapters.orEmpty()) }.getOrDefault(emptyList()) }
    val displayedPosition = if (chosen?.id == active?.id) position else chosen?.position ?: 0

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() },
            Executor { command -> context.mainExecutorCompat(command) })
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        while (true) {
            position = player.currentPosition
            playing = player.isPlaying
            liveId = player.currentMediaItem?.mediaId
            today = LocalDate.now()
            delay(if (playing) 250 else 750)
        }
    }
    DisposableEffect(endpoint, wifi) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (endpoint != null) Retrieval.request(context, wifi)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    LaunchedEffect(endpoint, wifi, prefs[Prefs.auto], prefs[Prefs.retention]) {
        if (endpoint != null) {
            Retrieval.schedule(context, wifi)
            Retrieval.request(context, wifi)
        }
    }
    LifecycleResumeEffect(endpoint, wifi) {
        if (endpoint != null && System.currentTimeMillis() - (prefs[Prefs.lastSync] ?: 0) > 300_000) {
            Retrieval.request(context, wifi)
        }
        onPauseOrDispose { }
    }

    fun select(episode: Episode) {
        save { it[Prefs.selected] = episode.id }
        if (active?.id != episode.id && episode.local?.let { !File(it).isFile } != false) {
            Retrieval.request(context, wifi, episode.id)
            return
        }
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId != episode.id) {
            val file = episode.local?.let(::File)?.takeIf { it.isFile } ?: return
            val segments = runCatching { chapters(episode.chapters) }.getOrDefault(emptyList())
            val media = MediaItem.Builder().setMediaId(episode.id).setUri(Uri.fromFile(file))
                .setMediaMetadata(PlaybackService.metadata(segments)).build()
            player.setMediaItem(media, resumePosition(episode.position, episode.duration, prefs[Prefs.resume] ?: true))
            player.setPlaybackSpeed(prefs[Prefs.speed] ?: 1f)
            player.prepare()
            player.pause()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color.Black,
        background = backgrounds[background], surface = cards[background], onSurface = Color(0xffeeeeee),
        surfaceVariant = cards[background], onSurfaceVariant = muted)) {
        Scaffold(containerColor = backgrounds[background], bottomBar = {
            NavigationBar(containerColor = backgrounds[background]) {
                listOf("Stations" to "☷", "Radio" to "◉", "Settings" to "⚙").forEach { (label, icon) ->
                    NavigationBarItem(selected = screen == label, onClick = { screen = label },
                        icon = { Text(icon, fontSize = 23.sp) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = accent.copy(alpha = .14f),
                            selectedIconColor = accent, selectedTextColor = accent))
                }
            }
        }) { insets ->
            BoxWithConstraints(Modifier.padding(insets).fillMaxSize()) {
                val landscape = maxWidth >= 650.dp || maxWidth > maxHeight * 1.25f
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(screen.uppercase(), color = accent, fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp, fontSize = 24.sp)
                    when (screen) {
                        "Radio" -> Radio(
                            today, visible, chosen, segmentList, displayedPosition, playing, landscape,
                            controller != null, prefs[Prefs.speed] ?: 1f,
                            onSelect = ::select,
                            onPlay = {
                                chosen?.let {
                                    if (controller?.currentMediaItem?.mediaId == it.id) {
                                        if (controller?.isPlaying == true) controller?.pause() else controller?.play()
                                    } else {
                                        select(it)
                                        if (it.local?.let { path -> File(path).isFile } == true) controller?.play()
                                    }
                                }
                            },
                            onSeek = { value ->
                                chosen?.takeIf { it.local != null }?.let {
                                    select(it)
                                    controller?.seekTo(value.coerceIn(0, it.duration))
                                }
                            },
                            onSpeed = {
                                val speed = speeds[(speeds.indexOf(prefs[Prefs.speed] ?: 1f) + 1) % speeds.size]
                                controller?.setPlaybackSpeed(speed)
                                save { it[Prefs.speed] = speed }
                            }
                        )
                        "Stations" -> Stations(status, visible, prefs, endpoint != null, {
                            Retrieval.request(context, wifi, "")
                        }, { screen = "Radio" })
                        else -> Settings(prefs, items, save, controller)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun Context.mainExecutorCompat(command: Runnable) {
    android.os.Handler(mainLooper).post(command)
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun StationSelector() {
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Box {
            Row(Modifier.fillMaxWidth().clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically) {
                Text("◉", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                Text("Morning Commute", Modifier.weight(1f).padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("⌄")
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(text = { Text("Morning Commute") }, onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun Radio(
    today: LocalDate, items: List<Episode>, episode: Episode?, segments: List<Chapter>, position: Long,
    playing: Boolean, landscape: Boolean, connected: Boolean, speed: Float, onSelect: (Episode) -> Unit,
    onPlay: () -> Unit, onSeek: (Long) -> Unit, onSpeed: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val duration = episode?.duration ?: 0
    val index = chapterIndex(segments.map { it.start }, position)
    val chapter = segments.getOrNull(index)
    val waveform = remember(episode?.chapters) {
        runCatching {
            val array = JSONObject(episode?.chapters.orEmpty()).getJSONArray("waveform")
            List(array.length().coerceAtMost(256)) { array.getDouble(it).toFloat().coerceIn(0f, 1f) }
        }.getOrDefault(emptyList())
    }
    var dayMenu by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var all by rememberSaveable { mutableStateOf(false) }
    StationSelector()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (days in 6 downTo 0) {
            val date = today.minusDays(days.toLong())
            val editions = items.filter { it.date == date.toString() }
            val selected = episode?.date == date.toString()
            val label = date.format(DateTimeFormatter.ofPattern("EEE")).uppercase()
            val card = if (selected) accent.copy(alpha = .18f) else MaterialTheme.colorScheme.surface
            Column(Modifier.weight(1f).background(card, shape)
                .border(1.dp, if (selected) accent else muted.copy(alpha = .18f), shape)
                .clickable(enabled = editions.isNotEmpty()) {
                    if (editions.size == 1) onSelect(editions.first()) else dayMenu = editions
                }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 10.sp, color = if (selected) accent else muted, maxLines = 1)
                Text(date.dayOfMonth.toString(), fontSize = 19.sp, color = if (selected) accent else Color.White)
                val local = editions.any { it.local != null }
                val remote = editions.any { it.remote }
                Row {
                    if (local) Text("●", color = green, fontSize = 9.sp)
                    if (remote) Text("●", color = blue, fontSize = 9.sp)
                    if (!local && !remote) Text("–", color = muted, fontSize = 9.sp)
                }
            }
        }
    }
    if (dayMenu.isNotEmpty()) AlertDialog(onDismissRequest = { dayMenu = emptyList() },
        title = { Text("Select edition") }, text = {
            Column {
                dayMenu.forEach { item ->
                    TextButton(onClick = { onSelect(item); dayMenu = emptyList() }) {
                        Text("Morning Edition · ${localTime(item.published)}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { dayMenu = emptyList() }) { Text("Close") } })

    val display: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Color(0xff090a0b), shape)
            .border(1.dp, muted.copy(alpha = .18f), shape).padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(episode?.date?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("MMM d")).uppercase() + " · MORNING EDITION" }
                    ?: "MORNING COMMUTE", color = accent, fontSize = 13.sp)
                if (bluetoothConnected()) Text("Bluetooth", color = blue, fontSize = 13.sp)
            }
            Text(chapter?.title ?: if (episode != null) "Morning Edition" else "No broadcast available",
                color = accent, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 2)
            chapter?.let { Text(it.reporter, color = muted) }
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Playback waveform" }) {
                waveform.forEachIndexed { i, peak ->
                    val factor = .06f + .94f * peak
                    val x = (i + .5f) * size.width / waveform.size
                    val mid = size.height / 2f
                    drawLine(accent.copy(alpha = if (i.toFloat() / waveform.size < fraction) 1f else .23f),
                        Offset(x, mid * (1 - factor)), Offset(x, mid * (1 + factor)),
                        strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            var seek by remember { mutableStateOf<Float?>(null) }
            Slider(value = seek ?: fraction, onValueChange = { seek = it },
                onValueChangeFinished = { seek?.let { onSeek((it * duration).toLong()) }; seek = null },
                enabled = duration > 0 && episode?.local != null,
                modifier = Modifier.height(32.dp).semantics { contentDescription = "Playback position" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(position), color = accent)
                Text(clock(duration), color = accent)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Control("│◀", "Previous segment", index > 0) { onSeek(segments[index - 1].start) }
                Control("↶ 15", "Rewind 15 seconds", episode?.local != null) { onSeek(position - 15_000) }
                FilledTonalButton(onClick = onPlay, enabled = connected && episode != null,
                    modifier = Modifier.size(76.dp).semantics {
                        contentDescription = if (playing) "Pause" else if (episode?.local == null) "Download" else "Play"
                    }, shape = CircleShape, contentPadding = PaddingValues(0.dp)) {
                    Text(if (playing) "Ⅱ" else if (episode?.local == null) "↓" else "▶", color = accent, fontSize = 30.sp)
                }
                Control("30 ↷", "Forward 30 seconds", episode?.local != null) { onSeek(position + 30_000) }
                Control("▶│", "Next segment", index + 1 < segments.size) { onSeek(segments[index + 1].start) }
            }
            if (segments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("SEGMENT ${index + 1} OF ${segments.size}", fontSize = 12.sp, color = muted)
                    TextButton(onClick = { all = !all }) { Text(if (all) "HIDE SEGMENTS" else "ALL SEGMENTS") }
                }
                BoxWithConstraints {
                    val width = maxWidth * .43f
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        segments.forEachIndexed { i, segment ->
                            val selected = i == index
                            Box(Modifier.width(width).height(48.dp)
                                .background(if (selected) accent.copy(alpha = .17f) else MaterialTheme.colorScheme.surface, shape)
                                .border(1.dp, if (selected) accent else muted.copy(alpha = .25f), shape)
                                .clickable { onSeek(segment.start) }, contentAlignment = Alignment.Center) {
                                Text(segment.title.uppercase(), fontSize = 11.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                            }
                        }
                    }
                }
                if (all) segments.forEach { segment ->
                    TextButton(onClick = { onSeek(segment.start) }) {
                        Text("${clock(segment.start)}  ${segment.title} · ${segment.reporter}")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSpeed) { Text("${speed}×") }
                Text(if (episode?.local != null) "● OFFLINE READY" else if (episode != null) "● DOWNLOAD REQUIRED" else "",
                    color = if (episode?.local != null) green else blue, fontSize = 12.sp)
            }
        }
    }
    if (landscape) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f)) { display() }
        Box(Modifier.weight(1f)) { controls() }
    } else {
        display()
        controls()
    }
}

@Composable
private fun Control(text: String, label: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(2.dp),
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
private fun bluetoothConnected(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AudioManager::class.java) }
    fun connected() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }
    var found by remember { mutableStateOf(connected()) }
    DisposableEffect(manager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { found = connected() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { found = connected() }
        }
        manager.registerAudioDeviceCallback(callback, android.os.Handler(context.mainLooper))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return found
}

@Composable
private fun Stations(status: String, episodes: List<Episode>, prefs: Preferences, configured: Boolean,
    retrieve: () -> Unit, openRadio: () -> Unit) {
    val json = remember(status) { runCatching { JSONObject(status) }.getOrNull() }
    val state = json?.optString("state")
    val release = json?.optString("release_at").orEmpty()
    val idle = state in listOf("Ready", "Failed", "Expired")
    val heartbeat = json?.optString("heartbeat_at").orEmpty()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = Instant.now() } }
    val heartbeatStale = runCatching { Instant.parse(heartbeat).plusSeconds(120) < now }.getOrDefault(false)
    val summary = if (state in listOf("In progress", "Awaiting release") && heartbeatStale) "Delayed"
        else state ?: "Scheduled"
    val next = json?.optString(if (idle) "next_release_at" else "release_at").orEmpty()
    val production = json?.optString(if (idle) "next_production_at" else "production_at").orEmpty()
    val latest = episodes.firstOrNull()
    Panel {
        TextButton(onClick = openRadio) { Text("◉  Morning Commute", style = MaterialTheme.typography.titleLarge) }
        Text("Daily · ${if (release.isBlank()) "7:00 AM Eastern" else localTime(release)}")
        HorizontalDivider()
        SettingValue("Production", summary, if (summary == "Ready") green else MaterialTheme.colorScheme.primary)
    }
    Panel {
        Text("Latest broadcast", style = MaterialTheme.typography.titleMedium)
        Text(latest?.let { "${it.date} · ${clock(it.duration)}" } ?: "No broadcast available", color = muted)
        if (latest?.local != null) Text("Downloaded", color = green)
        OutlinedButton(onClick = retrieve, enabled = configured, modifier = Modifier.fillMaxWidth()) {
            Text("Retrieve broadcast")
        }
        if (!configured) Text("Set the Supabase project URL in Settings.", color = muted)
    }
    Panel {
        Text("Next scheduled broadcast", style = MaterialTheme.typography.titleMedium)
        Text(if (next.isBlank()) "Daily at 7:00 AM Eastern" else localTime(next))
        if (production.isNotBlank()) Text("Production begins ${localTime(production)}", color = muted)
        SettingValue("Automatic retrieval", if (prefs[Prefs.auto] ?: true) "Enabled" else "Disabled", green)
    }
}

@Composable
private fun Settings(prefs: Preferences, episodes: List<Episode>,
    save: (suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) -> Unit,
    player: MediaController?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable(prefs[Prefs.endpoint]) { mutableStateOf(prefs[Prefs.endpoint] ?: BuildConfig.PROJECT_ORIGIN) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Panel {
        Text("Appearance", fontWeight = FontWeight.Bold)
        Text("Background", color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Charcoal", "Midnight Blue", "Dark Espresso").forEachIndexed { i, label ->
                Column(Modifier.weight(1f).clickable { save { it[Prefs.background] = i } },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(34.dp).background(backgrounds[i], shape).border(1.dp,
                        if ((prefs[Prefs.background] ?: 0) == i) MaterialTheme.colorScheme.primary else muted, shape))
                    Text(label, fontSize = 11.sp)
                }
            }
        }
        Text("Illumination", color = muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            accents.forEachIndexed { i, color ->
                val name = listOf("Amber", "Ice Blue", "Sport Red", "Emerald", "Purple", "White")[i]
                Box(Modifier.size(44.dp).semantics { contentDescription = name }
                    .clickable { save { it[Prefs.accent] = i } }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(27.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                        if ((prefs[Prefs.accent] ?: 0) == i) Text("✓", color = Color.Black)
                    }
                }
            }
        }
    }
    Panel {
        Text("Playback", fontWeight = FontWeight.Bold)
        SettingValue("Default speed", "${prefs[Prefs.speed] ?: 1f}×", action = { details = "Default speed" })
        Toggle("Resume playback", prefs[Prefs.resume] ?: true) { value -> save { it[Prefs.resume] = value } }
        SettingValue("Rewind / forward", "15s / 30s")
    }
    Panel {
        Text("Downloads", fontWeight = FontWeight.Bold)
        Toggle("Automatic download", prefs[Prefs.auto] ?: true) { value -> save { it[Prefs.auto] = value } }
        Toggle("Wi-Fi only (unmetered)", prefs[Prefs.wifi] ?: false) { value -> save { it[Prefs.wifi] = value } }
        SettingValue("Local retention", "${prefs[Prefs.retention] ?: 7} days", action = { details = "Local retention" })
        SettingValue("Cloud retention", "7 days")
    }
    Panel {
        Text("Storage", fontWeight = FontWeight.Bold)
        val stats = StatFs(context.filesDir.absolutePath)
        val bytes = episodes.sumOf { it.local?.let { path -> File(path).length() } ?: 0 }
        SettingValue("Device storage", "${size(stats.availableBytes)} free")
        SettingValue("Broadcast storage", size(bytes), action = { details = "Broadcast storage" })
    }
    Panel {
        Text("Connection", fontWeight = FontWeight.Bold)
        val sync = prefs[Prefs.lastSync]
        SettingValue("Last synchronization", sync?.let { localTime(Instant.ofEpochMilli(it).toString()) } ?: "Pending")
        OutlinedTextField(endpoint, { endpoint = it; invalid = false }, isError = invalid, singleLine = true,
            label = { Text("Supabase project URL") }, modifier = Modifier.fillMaxWidth(),
            supportingText = if (invalid) { { Text("Enter an HTTPS project origin without a path or credentials.") } } else null)
        OutlinedButton(onClick = {
            val raw = endpoint.trim().trimEnd('/')
            val uri = runCatching { URI(raw) }.getOrNull()
            invalid = uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                uri.query != null || uri.fragment != null || uri.path.isNotEmpty()
            if (!invalid) save { it[Prefs.endpoint] = raw }
        }) { Text("Save connection") }
    }
    Panel {
        Text("About", fontWeight = FontWeight.Bold)
        Text("Briefsona", style = MaterialTheme.typography.titleLarge)
        Text("The ultimate news experience")
        Text("Version 0.0.1 · MacGuyverism", color = muted)
        Text("Broadcast scripts and voices are generated with AI. Downloads and listening position are stored on this device.",
            color = muted, style = MaterialTheme.typography.bodySmall)
    }
    if (details.isNotBlank()) AlertDialog(onDismissRequest = { details = "" }, title = { Text(details) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                when (details) {
                    "Default speed" -> speeds.forEach { speed -> TextButton(onClick = {
                        save { it[Prefs.speed] = speed }; player?.setPlaybackSpeed(speed); details = ""
                    }) { Text("${speed}×") } }
                    "Local retention" -> (1..7).forEach { days -> TextButton(onClick = {
                        save { it[Prefs.retention] = days }; details = ""
                    }) { Text("$days ${if (days == 1) "day" else "days"}") } }
                    else -> episodes.filter { it.local != null }.forEach { episode ->
                        SettingValue(episode.date, "Delete download", action = {
                            if (episode.id != PlaybackService.activeId) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                if (episode.local?.let { File(it).delete() || !File(it).exists() } == true) {
                                    Library.get(context).episodes().downloaded(episode.id, null, "")
                                }
                            }
                        })
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = "" }) { Text("Close") } })
}

@Composable
private fun SettingValue(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary,
    action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (action == null) Modifier else Modifier.clickable(onClick = action))
        .padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, color = color)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, action: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = action, modifier = Modifier.semantics { contentDescription = label })
    }
}

private fun clock(ms: Long): String = "%d:%02d".format(Locale.ROOT, ms / 60_000, ms / 1000 % 60)
private fun size(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
private fun localTime(text: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(text))
}.getOrDefault("")
