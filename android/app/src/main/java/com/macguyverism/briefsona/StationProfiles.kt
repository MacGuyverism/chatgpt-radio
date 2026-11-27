package com.macguyverism.briefsona

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
    open suspend fun saveSchedule(draft: StationProfile, mode: StationMode) {
        val station = find(draft.id)?.decode() ?: if (draft.id == StationProfile.morningCommute.id) {
            StationProfile.morningCommute
        } else {
            StationProfile(id = draft.id, name = draft.name.trim(), segments = listOf(
                StationSegment(id = draft.segments.first().id, name = "Opening"),
                StationSegment(id = draft.segments.last().id, name = "Closing")
            ))
        }
        save(station.copy(mode = mode))
    }

    @Transaction
    open suspend fun saveSegment(profile: StationProfile, segment: StationSegment): StationProfile {
        val committed = (find(profile.id)?.decode() ?: profile).withSegment(segment)
        save(committed)
        return committed
    }

    @Transaction
    open suspend fun saveSoundBite(profile: StationProfile, segmentId: String, cue: String?): StationProfile {
        val saved = find(profile.id)?.decode() ?: profile
        val segment = saved.segments.firstOrNull { it.id == segmentId }
            ?: throw IllegalArgumentException("Save the segment before assigning a sound bite.")
        val committed = saved.withSegment(segment.copy(cue = cue))
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

internal val bundledCueGroups = linkedMapOf(
    "Intros" to listOf("cheesy_morning_news_tune", "news_music"),
    "Segments" to listOf("breaking_news_radio_jingle", "mini_news_jingle", "breaking_alert_news_intro_01", "breaking_news_intro_05"),
    "Outros" to listOf("minimalist_news_transition", "pulsing_electronic_news_intro_04"),
    "Existing cues" to listOf("intro", "international", "national", "local", "finance", "detour", "outro")
)
internal val bundledCues = bundledCueGroups.values.flatten()

internal fun bundledCueTitle(id: String): String =
    id.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

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
