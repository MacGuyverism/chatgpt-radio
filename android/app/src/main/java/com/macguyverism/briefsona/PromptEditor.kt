package com.macguyverism.briefsona

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

// Replace only this named section. Keep the user's preamble and other headings intact.
internal fun replaceSection(markdown: String, title: String, body: String): String {
    val header = Regex("(?m)^## " + Regex.escape(title) + "[ \\t]*$")
    val match = header.find(markdown)
    val replacement = "## $title\n" + body
    if (match == null) return listOf(markdown.trimEnd(), replacement).filter { it.isNotEmpty() }.joinToString("\n\n")
    val end = Regex("(?m)^#{1,2} ").find(markdown, match.range.last + 1)?.range?.first ?: markdown.length
    return markdown.substring(0, match.range.first) + replacement + (if (end < markdown.length) "\n\n" else "") + markdown.substring(end)
}

@Composable
internal fun StationPromptEditor(value: String, update: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val presets = remember { assets.open("station-prompts.json").bufferedReader().use { JSONObject(it.readText()) } }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }) { Text("Station instructions") }
    if (!expanded) return
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Structured editor" else "Edit Markdown") }
    if (advanced) {
        OutlinedTextField(value, update, label = { Text("Station Markdown") }, modifier = Modifier.fillMaxWidth())
        return
    }
    for (title in presets.keys()) {
        val options = presets.getJSONObject(title)
        val default = options.getString(options.keys().next())
        val heading = Regex("(?m)^## " + Regex.escape(title) + "[ \\t]*$").find(value)
        val section = heading?.let {
            val start = it.range.last + 1
            val end = Regex("(?m)^#{1,2} ").find(value, start)?.range?.first ?: value.length
            value.substring(start, end).trim()
        } ?: default
        var open by remember { mutableStateOf(false) }
        Text(title)
        Box {
            TextButton(onClick = { open = true }) { Text(options.keys().asSequence().firstOrNull { options.getString(it) == section } ?: "Custom") }
            DropdownMenu(open, { open = false }) {
                for (name in options.keys()) DropdownMenuItem(text = { Text(name) }, onClick = {
                    update(replaceSection(value, title, options.getString(name)))
                    open = false
                })
            }
        }
        OutlinedTextField(section, { update(replaceSection(value, title, it)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { update(replaceSection(value, title, default)) }) { Text("Reset $title") }
    }
}
