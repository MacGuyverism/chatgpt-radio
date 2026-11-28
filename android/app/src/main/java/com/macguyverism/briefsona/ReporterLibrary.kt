package com.macguyverism.briefsona

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

private data class Reporter(val id: String, val name: String, val voice: String, val base: String, val traits: List<Int>)
private val roster = listOf(
    Reporter("samantha", "Samantha Brooks", "Nova", "avatar_011", listOf(85,80,65,85,75,50)),
    Reporter("emily", "Emily Sullivan", "Coral", "avatar_012", listOf(90,80,40,65,70,65)),
    Reporter("victoria", "Victoria Bennett", "Shimmer", "avatar_013", listOf(70,80,55,95,85,60)),
    Reporter("michael", "Michael Reynolds", "Onyx", "avatar_001", listOf(50,95,25,65,95,75)),
    Reporter("daniel", "Daniel Mercer", "Echo", "avatar_002", listOf(60,85,35,55,80,95)),
    Reporter("mike", "Mike Jensen", "Ash", "avatar_003", listOf(85,65,90,75,75,55))
)
private val traits = listOf("Warmth", "Professionalism", "Humor", "Enthusiasm", "Confidence", "Analytical depth")

@Composable
internal fun ReporterLibrary(scroll: ScrollState, close: () -> Unit) {
    val context = LocalContext.current
    val art = remember(context) { ReporterArtwork(context) }
    val prefs = remember(context) { context.getSharedPreferences("reporter_profiles", Context.MODE_PRIVATE) }
    fun commit(change: android.content.SharedPreferences.Editor) {
        change.putLong("_revision", prefs.getLong("_revision", 0) + 1).apply()
        StationBackup.request(context)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) {
        val custom = prefs.all.keys.filter { it.startsWith("custom-") }.sorted().map { id ->
            roster.first().copy(id = id, name = "New reporter")
        }
        (roster + custom).filter { !prefs.getBoolean("hidden/" + it.id, false) }
    }
    val defaultId = prefs.getString("default", roster.first().id)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var document by rememberSaveable { mutableStateOf("{}") }
    var avatarPage by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selected, avatarPage) { scroll.scrollTo(0) }
    fun back() {
        if (avatarPage) avatarPage = false else if (selected != null) selected = null else close()
    }
    BackHandler(onBack = ::back)
    val reporter = entries.find { it.id == selected }
    val title = when {
        avatarPage -> "Reporter avatar"
        reporter != null -> "Reporter profile"
        else -> "Reporter library"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        IconButton(onClick = ::back, modifier = Modifier.align(Alignment.CenterStart)
            .semantics { contentDescription = "Back" }) { Text("<", color = MaterialTheme.colorScheme.primary) }
    }
    if (reporter == null) {
        OutlinedButton(onClick = {
            val id = "custom-" + java.util.UUID.randomUUID()
            commit(prefs.edit().putString(id, "{}"))
            refresh++
            document = "{}"
            selected = id
        }) { Text("Add reporter") }
        ReporterShare(null) { received ->
            val id = "custom-" + java.util.UUID.randomUUID()
            document = received.toString()
            commit(prefs.edit().putString(id, document))
            refresh++
            selected = id
        }
        LazyVerticalGrid(GridCells.Adaptive(112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.id }) { item ->
                val saved = JSONObject(prefs.getString(item.id, "{}") ?: "{}")
                val appearance = art.restore(saved.optJSONObject("appearance") ?: JSONObject(), art.bases.first { it.id == item.base })
                Column(Modifier.clickable { document = saved.toString(); selected = item.id }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Portrait(art, appearance, 128, Modifier.size(76.dp))
                    if (item.id == defaultId) Text("Default")
                    Text(saved.optString("name", item.name), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        return
    }
    val profile = remember(document) { JSONObject(document) }
    val fallback = art.bases.first { it.id == reporter.base }
    val appearance = art.restore(profile.optJSONObject("appearance") ?: JSONObject(), fallback)
    fun set(key: String, value: Any) { document = JSONObject(document).put(key, value).toString() }
    if (avatarPage) {
        AvatarEditor(art, appearance) { set("appearance", it.json()); avatarPage = false }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Portrait(art, appearance, 256, Modifier.size(88.dp))
        val name = profile.optString("name", reporter.name)
        OutlinedTextField(name.replaceFirst(' ', '\n').let { first ->
            val next = first.indexOf(' ')
            if (next < 0) first else first.replaceRange(next, next + 1, "\n")
        }, { set("name", it.replace('\n', ' ')) }, maxLines = 3, label = { Text("Reporter name") })
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { avatarPage = true }) { Text("Choose and customize avatar") }
    OutlinedButton(onClick = {
        val id = "custom-" + java.util.UUID.randomUUID()
        val copy = JSONObject(document).put("name", profile.optString("name", reporter.name) + " (copy)")
            .put("voice", profile.optString("voice", reporter.voice)).put("appearance", appearance.json())
        traits.forEachIndexed { i, trait -> copy.put(trait, profile.optInt(trait, reporter.traits[i])) }
        document = copy.toString()
        commit(prefs.edit().putString(id, document))
        refresh++
        selected = id
    }) { Text("Duplicate reporter") }
    OutlinedButton(enabled = reporter.id != defaultId, onClick = {
        commit(prefs.edit().putString("default", reporter.id))
        refresh++
    }) { Text("Set as default") }
    var confirmDelete by remember { mutableStateOf(false) }
    OutlinedButton(enabled = reporter.id != defaultId, onClick = { confirmDelete = true }) { Text("Delete reporter") }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete reporter?") },
        confirmButton = { TextButton(onClick = {
            val change = prefs.edit().remove(reporter.id)
            if (roster.any { it.id == reporter.id }) change.putBoolean("hidden/" + reporter.id, true)
            commit(change)
            confirmDelete = false
            selected = null
            refresh++
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (reporter in roster) OutlinedButton(onClick = { document = "{}" }) { Text("Restore factory profile") }
    var voicesOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(profile.optString("voice", reporter.voice), {}, readOnly = true,
            label = { Text("Voice") }, trailingIcon = { Text("⌄") })
        Box(Modifier.matchParentSize().clickable { voicesOpen = true }.semantics { contentDescription = "Choose voice" })
        DropdownMenu(voicesOpen, { voicesOpen = false }) {
            roster.forEach { DropdownMenuItem(text = { Text(it.voice) }, onClick = { set("voice", it.voice); voicesOpen = false }) }
        }
    }
    val speed = profile.optDouble("speaking_speed", 1.0).toFloat().coerceIn(0.75f, 1.5f)
    Text("Speaking speed · " + "%.2f×".format(speed))
    Slider(speed, { set("speaking_speed", (it * 100).roundToInt() / 100.0) },
        valueRange = 0.75f..1.5f, steps = 14, modifier = Modifier.semantics { contentDescription = "Speaking speed" })
    val defaultMarkdown = listOf("Personality" to "Warm and professional", "Speaking style" to "Conversational",
        "Vocabulary" to "Everyday", "Humor" to "Subtle", "Reporting approach" to "Factual",
        "Reporter interactions" to "Occasional", "Additional instructions" to "").joinToString("\n\n") { (title, text) -> "## $title\n$text" }
    val markdown = profile.optString("markdown", defaultMarkdown)
    val sections = linkedMapOf(
        "Personality" to listOf("Warm and professional", "Serious", "Cheerful", "Witty", "Enthusiastic"),
        "Speaking style" to listOf("Conversational", "Formal", "Casual", "Expressive", "Direct"),
        "Vocabulary" to listOf("Everyday", "Simple", "Sophisticated", "Technical"),
        "Humor" to listOf("Subtle", "None", "Witty", "Playful"),
        "Reporting approach" to listOf("Factual", "Analytical", "Explanatory", "Storytelling"),
        "Reporter interactions" to listOf("Occasional", "Reserved", "Conversational")
    )
    var advanced by rememberSaveable(reporter.id) { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Simple personality" else "Advanced Markdown") }
    if (advanced) {
        sections.forEach { (section, options) ->
            var choose by remember(section) { mutableStateOf(false) }
            Box {
                TextButton(onClick = { choose = true }) { Text("$section preset") }
                DropdownMenu(choose, { choose = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = {
                        set("markdown", replaceSection(markdown, section, option))
                        choose = false
                    }) }
                }
            }
        }
    }
    if (advanced) OutlinedTextField(markdown, { set("markdown", it) }, label = { Text("Reporter Markdown") },
        modifier = Modifier.fillMaxWidth())
    traits.forEachIndexed { index, label ->
        val value = profile.optInt(label, reporter.traits[index]).coerceIn(0, 100)
        Text("$label · $value%")
        Slider(value.toFloat(), { set(label, it.roundToInt()) }, valueRange = 0f..100f, steps = 19,
            modifier = Modifier.semantics { contentDescription = label })
    }
    val delivery = markdown + "\n" + traits.mapIndexed { index, label ->
        "$label: " + profile.optInt(label, reporter.traits[index]) + "%"
    }.joinToString("\n")
    val portable = JSONObject().put("name", profile.optString("name", reporter.name))
        .put("appearance", appearance.json()).put("voice", profile.optString("voice", reporter.voice))
        .put("speaking_speed", speed.toDouble()).put("markdown", markdown)
    traits.forEachIndexed { i, label -> portable.put(label, profile.optInt(label, reporter.traits[i])) }
    ReporterShare(portable) {}
    PersonalityPreview(reporter.id, profile.optString("voice", reporter.voice), speed, delivery)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = { val reset = JSONObject(document); traits.forEach { reset.remove(it) }; document = reset.toString() }) { Text("Reset personality") }
    Button(onClick = { commit(prefs.edit().putString(reporter.id, document)); selected = null },
        enabled = profile.optString("name", reporter.name).isNotBlank()) { Text("Save reporter") }
    }
    }
}

@Composable
private fun Portrait(art: ReporterArtwork, appearance: Appearance, size: Int, modifier: Modifier) {
    val image = remember(art, appearance, size) { art.bitmap(appearance, size).asImageBitmap() }
    Image(image, null, modifier)
}

@Composable
private fun AvatarEditor(art: ReporterArtwork, initial: Appearance, apply: (Appearance) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial.json().toString()) }
    val value = remember(draft) { art.restore(JSONObject(draft), art.bases.first()) }
    val base = art.bases.first { it.id == value.id }
    var category by rememberSaveable { mutableStateOf(base.category) }
    fun update(next: Appearance) { draft = next.json().toString() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Portrait(art, value, 256, Modifier.size(160.dp)) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        art.bases.map { it.category }.distinct().forEach {
            FilterChip(category == it, { category = it }, label = { Text(it.replaceFirstChar(Char::uppercase)) })
        }
    }
    Text("Base avatar")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(art.bases.filter { it.category == category }, key = { it.id }) { item ->
            val modifier = Modifier.size(56.dp).selectable(item.id == value.id, role = Role.RadioButton) {
                update(value.copy(id = item.id, style = value.style.takeIf { it in item.styles } ?: item.defaults.style))
            }.semantics { contentDescription = "Avatar " + item.id.substringAfter('_').toInt() }
            Portrait(art, item.defaults, 128, if (item.id == value.id) modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else modifier)
        }
    }
    Text("Clothing style")
    listOf(listOf("suit", "collared"), listOf("casual", "blouse")).forEach { pair ->
        Row(Modifier.fillMaxWidth()) {
        pair.filter { it in base.styles }.forEach { style ->
        Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(value.style == style, role = Role.RadioButton) {
            update(value.copy(style = style))
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(value.style == style, null)
            Spacer(Modifier.width(10.dp))
            Text(if (style == "collared") "Collared shirt" else style.replaceFirstChar(Char::uppercase))
        }
    }
    }
    }
    listOf("clothing_color" to "Clothing color", "skin_color" to "Skin color", "hair_color" to "Hair color",
        "background_color" to "Background circle").forEach { (key, label) ->
        Text(label)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            art.palettes.getValue(key).forEachIndexed { index, hex ->
                val selected = value.colors[key] == hex
                Box(Modifier.size(48.dp).selectable(selected, role = Role.RadioButton) {
                    update(value.copy(colors = value.colors + (key to hex)))
                }.semantics { contentDescription = "$label ${index + 1}, $hex" }, contentAlignment = Alignment.Center) {
                    val swatch = Modifier.size(32.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape)
                    Box(if (selected) swatch.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else swatch)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wear glasses", Modifier.weight(1f))
        Switch(value.glasses, { update(value.copy(glasses = it)) }, modifier = Modifier.semantics { contentDescription = "Wear glasses" })
    }
    OutlinedButton(onClick = { update(base.defaults); category = base.category }, modifier = Modifier.fillMaxWidth()) { Text("Reset appearance") }
    Button(onClick = { apply(value) }, modifier = Modifier.fillMaxWidth()) { Text("Use appearance") }
}
}

@Composable
internal fun ReporterAssignment(selected: String?, select: (String?) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("reporter_profiles", Context.MODE_PRIVATE)
    val reporters = (roster.map { it.id to it.name } + prefs.all.keys.filter { it.startsWith("custom-") }.sorted().map { it to "New reporter" })
        .filterNot { prefs.getBoolean("hidden/" + it.first, false) }.associate { (id, name) ->
            id to JSONObject(prefs.getString(id, "{}") ?: "{}").optString("name", name)
        }
    val defaultName = reporters[prefs.getString("default", roster.first().id)]
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Reporter · " + (reporters[selected] ?: "Default ($defaultName)")) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Use default reporter") }, onClick = { select(null); open = false })
            reporters.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(id); open = false }) }
        }
    }
}
