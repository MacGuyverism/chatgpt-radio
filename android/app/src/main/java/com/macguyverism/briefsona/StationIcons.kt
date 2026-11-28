package com.macguyverism.briefsona

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.json.JSONArray

private data class IconChoice(val id: String, val label: String, val file: String)
private val palette = listOf("#F5B144", "#66CAFF", "#55DB96", "#BF9AF5", "#FF7680", "#F39054", "#EEECEF", "#58CBC7")

@Composable
private fun choices(): List<IconChoice> {
    val assets = LocalContext.current.assets
    return remember(assets) {
        val entries = assets.open("station-icons/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
        List(entries.length()) { i -> entries.getJSONObject(i).let { IconChoice(it.getString("id"), it.getString("label"), it.getString("file")) } }
    }
}

@Composable
internal fun StationIcon(id: String, color: String) {
    val assets = LocalContext.current.assets
    val icons = choices()
    val entry = icons.find { it.id == id }
    if (entry == null && id != "◉" && id.isNotBlank()) { Text(id); return }
    val icon = entry ?: icons.first()
    val bitmap = remember(icon, color) {
        val svg = assets.open("station-icons/" + icon.file).bufferedReader().use { it.readText() }
        val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        SVG.getFromString(svg.replace("currentColor", color.takeIf { it in palette } ?: palette.first()))
            .renderToCanvas(Canvas(image), RectF(0f, 0f, 96f, 96f))
        image.asImageBitmap()
    }
    Image(bitmap, icon.label, Modifier.size(32.dp))
}

@Composable
internal fun StationIconPicker(profile: StationProfile, edit: (StationProfile) -> Unit) {
    val icons = choices()
    Text("Station icon")
    StationIcon(profile.icon, profile.iconColor)
    LazyRow {
        items(icons, key = { it.id }) { icon ->
            IconButton(onClick = { edit(profile.copy(icon = icon.id)) }) { StationIcon(icon.id, profile.iconColor) }
        }
    }
    LazyRow {
        items(palette) { color ->
            FilterChip(profile.iconColor == color, { edit(profile.copy(iconColor = color)) },
                label = { Text("●", color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(color))) })
        }
    }
}
