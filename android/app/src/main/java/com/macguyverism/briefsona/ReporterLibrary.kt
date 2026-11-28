package com.macguyverism.briefsona

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
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var document by rememberSaveable { mutableStateOf("{}") }
    var avatarPage by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selected, avatarPage) { scroll.scrollTo(0) }
    fun back() {
        if (avatarPage) avatarPage = false else if (selected != null) selected = null else close()
    }
    BackHandler(onBack = ::back)
    TextButton(onClick = ::back) { Text("←  " + when { avatarPage -> "Reporter profile"; selected != null -> "Reporter library"; else -> "Station profile" }) }
    val reporter = roster.find { it.id == selected }
    if (reporter == null) {
        Text("Reporter library", style = MaterialTheme.typography.headlineSmall)
        roster.forEach { item ->
            val saved = JSONObject(prefs.getString(item.id, "{}") ?: "{}")
            OutlinedButton(onClick = { document = saved.toString(); selected = item.id }, modifier = Modifier.fillMaxWidth()) {
                Text(saved.optString("name", item.name))
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
    Text("Reporter profile", style = MaterialTheme.typography.headlineSmall)
    Portrait(art, appearance, 256, Modifier.size(100.dp))
    OutlinedButton(onClick = { avatarPage = true }) { Text("Choose and customize avatar") }
    OutlinedTextField(profile.optString("name", reporter.name), { set("name", it) }, label = { Text("Reporter name") })
    var voicesOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { voicesOpen = true }) { Text("Voice · " + profile.optString("voice", reporter.voice)) }
        DropdownMenu(voicesOpen, { voicesOpen = false }) {
            roster.forEach { DropdownMenuItem(text = { Text(it.voice) }, onClick = { set("voice", it.voice); voicesOpen = false }) }
        }
    }
    traits.forEachIndexed { index, label ->
        val value = profile.optInt(label, reporter.traits[index]).coerceIn(0, 100)
        Text("$label · $value%")
        Slider(value.toFloat(), { set(label, it.roundToInt()) }, valueRange = 0f..100f, steps = 19,
            modifier = Modifier.semantics { contentDescription = label })
    }
    OutlinedButton(onClick = { val reset = JSONObject(document); traits.forEach { reset.remove(it) }; document = reset.toString() }) { Text("Reset personality") }
    Button(onClick = { prefs.edit().putString(reporter.id, document).apply(); selected = null },
        enabled = profile.optString("name", reporter.name).isNotBlank()) { Text("Save reporter") }
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
    Text("Reporter avatar", style = MaterialTheme.typography.headlineSmall)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Portrait(art, value, 256, Modifier.size(160.dp)) }
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
    base.styles.forEach { style ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(value.style == style, role = Role.RadioButton) {
            update(value.copy(style = style))
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(value.style == style, null)
            Spacer(Modifier.width(10.dp))
            Text(if (style == "collared") "Collared shirt" else style.replaceFirstChar(Char::uppercase))
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
