package com.macguyverism.briefsona

import android.media.MediaPlayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
internal fun PersonalityPreview(id: String, voice: String, speed: Float, personality: String) {
    val context = LocalContext.current
    val titles = remember {
        val entries = context.assets.open("preview-headlines.json").bufferedReader().use { JSONArray(it.readText()) }
        List(entries.length()) { entries.getJSONObject(it).getString("headline") }
    }
    var headline by rememberSaveable(id) { mutableStateOf(titles.first()) }
    var menu by remember { mutableStateOf(false) }
    val fingerprint = remember(voice, speed, personality) {
        MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(voice,speed,personality)).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    val folder = File(context.filesDir, "reporter-previews/$id")
    val file = File(folder, "$fingerprint.mp3")
    var ready by remember(file) { mutableStateOf(file.isFile) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(file) { onDispose { player?.release(); player = null } }
    val scope = rememberCoroutineScope()
    val sample = "reporter-voices/" + voice.lowercase() + ".mp3"
    val available = remember(voice) { context.assets.list("reporter-voices")?.contains(voice.lowercase() + ".mp3") == true }
    OutlinedButton(enabled = available, onClick = {
        player?.release()
        player = MediaPlayer()
        try {
            context.assets.openFd(sample).use {
                player?.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player?.prepare()
            player?.start()
        } catch (_: Exception) { message = "Could not play the voice sample." }
    }) { Text("Hear voice") }
    Text("Hear personality")
    TextButton(onClick = { menu = true }) { Text(headline) }
    DropdownMenu(menu, { menu = false }) {
        (titles + "Random headline").forEach { title -> DropdownMenuItem(text = { Text(title) }, onClick = {
            headline = if (title in titles) title else titles.random()
            menu = false
        }) }
    }
    Button(enabled = !busy, onClick = {
        busy = true
        message = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val origin = BuildConfig.STATION_BACKUP_ORIGIN
                    val key = BuildConfig.STATION_BACKUP_KEY
                    require(origin.isNotBlank() && key.isNotBlank())
                    val session = StationBackupApi(context, origin, key).session()
                    val connection = URL("$origin/functions/v1/reporter-preview").openConnection() as HttpURLConnection
                    folder.mkdirs()
                    val temp = File.createTempFile("pending", ".tmp", folder)
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 180000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer " + session.token)
                        connection.setRequestProperty("apikey", key)
                        connection.setRequestProperty("Content-Type", "application/json")
                        val payload = JSONObject().put("headline", headline).put("voice", voice.lowercase())
                            .put("speed", speed.toDouble()).put("personality", personality)
                        connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                        require(connection.responseCode == 200 && connection.contentType?.startsWith("audio/") == true)
                        connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                        require(temp.length() > 0 && temp.renameTo(file))
                        folder.listFiles()?.filter { it != file && it.extension == "mp3" }?.forEach { it.delete() }
                    } finally { connection.disconnect(); temp.delete() }
                }
                ready = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Could not generate preview. Check your connection and preview service." }
            finally { busy = false }
        }
    }) { Text(if (busy) "Generating…" else "Generate fictional preview") }
    OutlinedButton(enabled = ready, onClick = {
        player?.release()
        player = MediaPlayer()
        try { player?.apply { setDataSource(file.absolutePath); prepare(); start() } }
        catch (_: Exception) { message = "Could not play this recording." }
    }) { Text("Play saved recording") }
    if (message.isNotEmpty()) Text(message)
}
