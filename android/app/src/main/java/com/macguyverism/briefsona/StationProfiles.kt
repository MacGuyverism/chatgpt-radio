package com.macguyverism.briefsona

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class StationMode(val label: String) {
    MANUAL("Manual"), SCHEDULED("Scheduled"), PAUSED("Scheduled (paused)")
}

internal data class StationSegment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val weight: Double = 1.0,
    val cue: String? = null,
    val enabled: Boolean = true,
    val instructions: String = ""
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("weight", weight)
        .put("cue", cue ?: JSONObject.NULL).put("enabled", enabled).put("instructions", instructions)

    companion object {
        fun from(json: JSONObject) = StationSegment(
            id = json.getString("id"), name = json.getString("name"), weight = json.getDouble("weight"),
            cue = json.optString("cue").takeUnless { json.isNull("cue") },
            enabled = json.optBoolean("enabled", true), instructions = json.optString("instructions")
        )
    }
}

internal data class StationProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val minutes: Int = 30,
    val mode: StationMode = StationMode.MANUAL,
    val random: Boolean = false,
    val segments: List<StationSegment> = listOf(StationSegment(name = "Opening"), StationSegment(name = "Closing")),
    val icon: String = "◉",
    val instructions: String = ""
) {
    fun duplicate() = copy(
        id = UUID.randomUUID().toString(), name = "$name (copy)",
        segments = segments.map { it.copy(id = UUID.randomUUID().toString()) }
    )

    fun withSegment(segment: StationSegment): StationProfile {
        val updated = segments.toMutableList()
        val index = updated.indexOfFirst { it.id == segment.id }
        if (index < 0) updated.add(updated.lastIndex, segment) else updated[index] = segment
        return copy(segments = updated)
    }

    fun validationError(): String? = when {
        name.isBlank() -> "Enter a station name."
        minutes !in 5..60 -> "Enter a duration from 5 to 60 minutes."
        segments.size - 2 > minutes / 3 -> "This duration allows up to ${minutes / 3} custom segments."
        segments.size < 2 || !segments.first().enabled || !segments.last().enabled ->
            "Opening and Closing must stay enabled."
        segments.any { it.name.isBlank() } -> "Give every segment a name."
        segments.any { !it.weight.isFinite() || it.weight <= 0 } -> "Give every segment a positive relative airtime."
        else -> null
    }

    fun json() = JSONObject().put("id", id).put("name", name).put("minutes", minutes)
        .put("icon", icon).put("instructions", instructions)
        .put("mode", mode.name).put("random", random).put("segments", JSONArray().apply {
            segments.forEachIndexed { index, segment ->
                put(segment.json()
                    .put("role", when (index) {
                        0 -> "opening"
                        segments.lastIndex -> "closing"
                        else -> "custom"
                    }))
            }
        })

    companion object {
        val morningCommute = StationProfile(
            id = UUID.nameUUIDFromBytes("morning-commute".toByteArray()).toString(),
            name = "Morning Commute", mode = StationMode.SCHEDULED
        )

        fun from(json: JSONObject): StationProfile {
            val segments = json.getJSONArray("segments")
            return StationProfile(json.getString("id"), json.getString("name"), json.getInt("minutes"),
                StationMode.valueOf(json.getString("mode")), json.getBoolean("random"),
                List(segments.length()) { index ->
                    StationSegment.from(segments.getJSONObject(index))
                }, json.optString("icon", "◉"), json.optString("instructions"))
        }
    }
}

@Entity
internal data class StationRecord(
    @PrimaryKey val id: String,
    val name: String,
    val profile: String,
    val revision: Long,
    val backup: String
) {
    fun decode() = StationProfile.from(JSONObject(profile))
}

@Dao
internal abstract class StationDao {
    @Query("SELECT * FROM StationRecord ORDER BY name COLLATE NOCASE, id")
    abstract fun observe(): Flow<List<StationRecord>>

    @Query("SELECT * FROM StationRecord WHERE backup != 'Backed up'")
    abstract suspend fun pending(): List<StationRecord>

    @Query("SELECT * FROM StationRecord WHERE id = :id")
    abstract suspend fun find(id: String): StationRecord?

    @Query("SELECT revision FROM StationRecord WHERE id = :id")
    abstract suspend fun revision(id: String): Long?

    @Upsert
    abstract suspend fun put(record: StationRecord)

    @Query("UPDATE StationRecord SET backup = :status WHERE id = :id AND revision = :revision")
    abstract suspend fun mark(id: String, revision: Long, status: String)

    @Transaction
    open suspend fun saveSegment(profile: StationProfile, segment: StationSegment): StationProfile {
        val committed = (find(profile.id)?.decode() ?: profile).withSegment(segment)
        save(committed)
        return committed
    }

    @Transaction
    open suspend fun saveLineup(draft: StationProfile) {
        val saved = find(draft.id)?.decode() ?: draft
        save(saved.copy(segments = draft.segments, random = draft.random))
    }

    @Transaction
    open suspend fun save(profile: StationProfile) {
        profile.validationError()?.let { throw IllegalArgumentException(it) }
        put(StationRecord(profile.id, profile.name, profile.json().toString(),
            (revision(profile.id) ?: 0) + 1, "Backup pending"))
    }
}

@Database(entities = [StationRecord::class], version = 1, exportSchema = false)
internal abstract class StationDatabase : RoomDatabase() {
    abstract fun stations(): StationDao

    companion object {
        @Volatile private var instance: StationDatabase? = null

        fun get(context: Context): StationDao = (instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StationDatabase::class.java, "station-profiles")
                .build().also { instance = it }
        }).stations()
    }
}

internal val bundledCues = listOf("intro", "international", "national", "local", "finance", "detour", "outro")

internal fun importedCueFile(context: Context, cue: String): File =
    File(File(context.filesDir, "station-cues"), UUID.fromString(cue.removePrefix("file:")).toString())

internal suspend fun importStationCue(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val cue = "file:${UUID.randomUUID()}"
    val file = importedCueFile(context, cue)
    file.parentFile?.mkdirs()
    try {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 45L * 1024 * 1024) { "Clip is larger than 45 MB." }
                    output.write(buffer, 0, count)
                }
            }
        }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } finally {
            metadata.release()
        }
        cue
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
