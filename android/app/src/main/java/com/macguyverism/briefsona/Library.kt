package com.macguyverism.briefsona

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

val Context.settings by preferencesDataStore("briefsona")

object Prefs {
    val background = intPreferencesKey("background")
    val accent = intPreferencesKey("accent")
    val speed = floatPreferencesKey("speed")
    val resume = booleanPreferencesKey("resume")
    val auto = booleanPreferencesKey("automatic")
    val wifi = booleanPreferencesKey("wifi")
    val retention = intPreferencesKey("retention")
    val endpoint = stringPreferencesKey("endpoint")
    val status = stringPreferencesKey("status")
    val retrieval = stringPreferencesKey("retrieval_result")
    val lastSync = longPreferencesKey("last_sync")
    val selected = stringPreferencesKey("selected")
}

@Entity
data class Episode(
    @PrimaryKey val id: String,
    val date: String,
    val audio: String,
    val chapters: String,
    val duration: Long,
    val local: String? = null,
    val position: Long = 0,
    val remote: Boolean = true,
    val published: String = ""
)

data class Chapter(val title: String, val reporter: String, val start: Long, val end: Long)

fun chapters(json: String): List<Chapter> {
    if (json.isBlank()) return emptyList()
    val metadata = JSONObject(json)
    require(metadata.getInt("schema_version") == 1)
    val array = metadata.getJSONArray("chapters")
    require(array.length() in 1..100)
    var end = 0L
    return List(array.length()) { i ->
        val item = array.getJSONObject(i)
        val chapter = Chapter(item.getString("title"), item.getString("reporter"),
            item.getLong("start_ms"), item.getLong("end_ms"))
        require(chapter.start == end && chapter.end > end)
        end = chapter.end
        chapter
    }.also { require(end == metadata.getLong("duration_ms")) }
}

fun chapterIndex(starts: List<Long>, position: Long): Int =
    starts.indexOfLast { it <= position }.coerceAtLeast(0)

fun resumePosition(saved: Long, duration: Long, enabled: Boolean): Long =
    if (enabled && saved >= 0 && saved < duration) saved else 0

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Episode ORDER BY date DESC, published DESC")
    fun observe(): Flow<List<Episode>>

    @Query("SELECT * FROM Episode")
    suspend fun all(): List<Episode>

    @Query("SELECT * FROM Episode WHERE id = :id")
    suspend fun find(id: String): Episode?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(episode: Episode)

    @Query("UPDATE Episode SET date=:date, audio=:audio, duration=:duration, published=:published, remote=1 WHERE id=:id")
    suspend fun remote(id: String, date: String, audio: String, duration: Long, published: String)

    @Query("UPDATE Episode SET local=:path, chapters=:chapters WHERE id=:id")
    suspend fun downloaded(id: String, path: String?, chapters: String)

    @Query("UPDATE Episode SET position=:position WHERE id=:id")
    suspend fun position(id: String, position: Long)

    @Query("UPDATE Episode SET remote=0")
    suspend fun markUnavailable()
}

@Database(entities = [Episode::class], version = 1, exportSchema = false)
abstract class Library : RoomDatabase() {
    abstract fun episodes(): LibraryDao

    companion object {
        @Volatile private var instance: Library? = null
        fun get(context: Context): Library = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Library::class.java, "briefsona")
                .build().also { instance = it }
        }
    }
}

private class MissingObject : IOException()
private const val MAX_AUDIO = 45L * 1024 * 1024

private fun <T> read(url: String, consume: (InputStream, Long) -> T): T {
    val endpoint = URL(url)
    require(endpoint.protocol == "https" && endpoint.userInfo == null && endpoint.host.isNotBlank())
    val connection = endpoint.openConnection() as HttpURLConnection
    connection.connectTimeout = 12_000
    connection.readTimeout = 20_000
    connection.instanceFollowRedirects = false
    try {
        if (connection.responseCode == 404) throw MissingObject()
        if (connection.responseCode == 400) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readLine().orEmpty().take(1024) }.orEmpty()
            if (message.contains("not found", ignoreCase = true) || message.contains("not_found", ignoreCase = true)) {
                throw MissingObject()
            }
        }
        if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
        return connection.inputStream.use { consume(it, connection.contentLengthLong) }
    } finally {
        connection.disconnect()
    }
}

private fun readJson(url: String): String = read(url) { stream, _ ->
    val buffer = ByteArray(1_048_577)
    var size = 0
    while (size < buffer.size) {
        val count = stream.read(buffer, size, buffer.size - size)
        if (count < 0) break
        size += count
    }
    require(size < buffer.size) { "Metadata too large" }
    String(buffer, 0, size, Charsets.UTF_8)
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun copyVerified(stream: InputStream, file: File, size: Long, hash: String) {
    require(size in 1..MAX_AUDIO)
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    file.outputStream().use { output ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            bytes += count
            if (bytes > size) throw IOException("Oversized broadcast")
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        output.fd.sync()
    }
    if (bytes != size || (hash.isNotBlank() && digest.digest().hex() != hash.lowercase())) {
        throw IOException("Broadcast verification failed")
    }
}

class Retrieval(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            syncLock.withLock {
                applicationContext.settings.edit { it[Prefs.retrieval] = "Retrieving" }
                sync()
                applicationContext.settings.edit { it[Prefs.retrieval] = "Connected" }
            }
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            val attempt = inputData.getInt("retry_attempt", 0)
            applicationContext.settings.edit {
                it[Prefs.retrieval] = if (attempt < 12) "Retrieval delayed" else "Retrieval failed"
            }
            if (attempt < 12) {
                val wifi = applicationContext.settings.data.first()[Prefs.wifi] ?: false
                request(applicationContext, wifi, inputData.getString("episode"), 300_000,
                    retryAttempt = attempt + 1, retrySeries = inputData.getString("retry_series") ?: id.toString())
            }
            Result.failure()
        }
    }

    private suspend fun sync() {
        val context = applicationContext
        val prefs = context.settings.data.first()
        val project = (prefs[Prefs.endpoint] ?: BuildConfig.PROJECT_ORIGIN).trimEnd('/').ifBlank { return }
        val base = "$project/storage/v1/object/public/chatgpt-radio"
        val status = try { readJson("$base/status.json") } catch (_: MissingObject) { "" }
        val records = try {
            JSONObject(readJson("$base/archive.json")).getJSONArray("episodes").let { items ->
                List(items.length()) { items.getJSONObject(it) }
            }
        } catch (_: MissingObject) {
            try { listOf(JSONObject(readJson("$base/latest.json"))) }
            catch (_: MissingObject) { throw IOException("No broadcast index available") }
        }.sortedByDescending { it.optString("published_at", it.getString("date")) }
        val database = Library.get(context)
        val dao = database.episodes()
        val episodes = records.map { item ->
            val id = item.getString("path")
            require(id.matches(Regex("episodes/chatgpt-radio-[0-9-]+(?:-[0-9a-f]{32})?\\.mp3")))
            val date = LocalDate.parse(item.getString("date")).toString()
            val published = item.optString("published_at")
            Episode(id, date, "$base/$id", "", (item.getDouble("duration_seconds") * 1000).toLong(),
                published = published) to item
        }
        database.withTransaction {
            dao.markUnavailable()
            for ((episode, _) in episodes) {
                dao.insert(episode)
                dao.remote(episode.id, episode.date, episode.audio, episode.duration, episode.published)
            }
        }
        context.settings.edit {
            it[Prefs.status] = status
            it[Prefs.lastSync] = System.currentTimeMillis()
        }
        releaseDelay(status)?.let { request(context, prefs[Prefs.wifi] ?: false, delay = it) }
        val cutoff = LocalDate.now().minusDays((prefs[Prefs.retention] ?: 7).toLong() - 1).toString()
        for (episode in dao.all()) {
            val path = episode.local ?: continue
            if (episode.date < cutoff && episode.id != PlaybackService.activeId) {
                if (!File(path).exists() || File(path).delete()) dao.downloaded(episode.id, null, "")
            }
        }
        val requested = inputData.getString("episode")
        val manual = if (requested == "") episodes.firstOrNull()?.first?.id ?: return else requested
        val auto = prefs[Prefs.auto] ?: true
        if (!auto && manual == null) return
        var failure: Exception? = null
        for ((episode, item) in episodes) {
            if (episode.date < cutoff && manual != episode.id) continue
            if (manual != null && manual != episode.id) continue
            if (dao.find(episode.id)?.local?.let { File(it).isFile } == true) continue
            try {
                download(episode, item, base, dao)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
        if (status.isNotBlank() && manual == null) {
            val state = JSONObject(status)
            val elapsed = java.time.Duration.between(Instant.parse(state.getString("release_at")), Instant.now())
            val pending = state.optString("state") in listOf("In progress", "Awaiting release", "Delayed")
            if (pending && !elapsed.isNegative && elapsed.toMinutes() < 60) {
                throw IOException("Waiting for scheduled release")
            }
        }
    }

    private suspend fun download(episode: Episode, item: JSONObject, base: String, dao: LibraryDao) {
        val chapterHash = item.optString("chapter_sha256")
        val chapterData = if (item.has("chapter_url")) {
            readJson("$base/${episode.id.removeSuffix(".mp3")}.json")
        } else ""
        if (chapterHash.isNotBlank()) {
            require(MessageDigest.getInstance("SHA-256").digest(chapterData.toByteArray()).hex() == chapterHash)
        }
        chapters(chapterData)
        val directory = File(applicationContext.filesDir, "broadcasts").apply { mkdirs() }
        val destination = File(directory, episode.id.substringAfterLast('/'))
        val temp = File.createTempFile("broadcast-", ".part", directory)
        try {
            val hash = item.optString("sha256")
            read(episode.audio) { stream, length ->
                copyVerified(stream, temp, item.optLong("bytes", length), hash)
            }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(temp.absolutePath)
                require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                require(kotlin.math.abs(duration - episode.duration) <= 2_000)
            } finally {
                reader.release()
            }
            check(temp.renameTo(destination))
            dao.downloaded(episode.id, destination.absolutePath, chapterData)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private val syncLock = Mutex()

        fun schedule(context: Context, wifi: Boolean) {
            val constraints = constraints(wifi)
            val work = PeriodicWorkRequestBuilder<Retrieval>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("archive-periodic",
                ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun request(context: Context, wifi: Boolean, episode: String? = null, delay: Long = 0,
            retryAttempt: Int = 0, retrySeries: String? = null) {
            val work = OneTimeWorkRequestBuilder<Retrieval>()
                .setInputData(Data.Builder().apply {
                    if (episode != null) putString("episode", episode)
                    if (retrySeries != null) putString("retry_series", retrySeries)
                    putInt("retry_attempt", retryAttempt)
                }.build())
                .setConstraints(constraints(wifi))
                .setInitialDelay(delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                when {
                    retrySeries != null -> "archive-retry-$retrySeries-$retryAttempt"
                    delay > 0 -> "archive-release"
                    episode == null -> "archive-refresh"
                    else -> "archive-manual-$episode"
                },
                ExistingWorkPolicy.KEEP, work)
        }

        private fun constraints(wifi: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    }
}

fun releaseDelay(status: String): Long? = runCatching {
    val json = JSONObject(status)
    val field = if (json.optString("state") in listOf("Ready", "Failed")) "next_release_at" else "release_at"
    Instant.parse(json.getString(field)).toEpochMilli() - System.currentTimeMillis()
}.getOrNull()?.takeIf { it > 0 }
