package com.macguyverism.briefsona

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class StationProfileTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var database: StationDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            compose.activity.applicationContext, StationDatabase::class.java
        ).build()
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun createPauseAndReopenStation() {
        val name = "Station ${UUID.randomUUID()}"
        compose.onNodeWithText("Stations").performClick()
        compose.onNodeWithContentDescription("Add station").performClick()
        compose.onNodeWithText(StationProfile.DEFAULT_STATION_INSTRUCTIONS).assertExists()
        val target = compose.onNodeWithContentDescription("Target duration")
        target.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "30 minutes"))
        for (minutes in listOf(5, 37, 60)) {
            target.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(minutes.toFloat()) }
            target.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$minutes minutes"))
        }
        compose.onNodeWithText("Station name *").performScrollTo().performTextInput(name)
        compose.onNodeWithText("Schedule · Manual").performScrollTo().performClick()
        compose.onNodeWithText("Produce broadcast now").assertIsNotEnabled()
        compose.onNodeWithText("Retry production").assertIsNotEnabled()
        compose.onNodeWithText("Automatic").performClick()
        compose.onNodeWithText("Pause schedule").performScrollTo().performClick()
        compose.onNodeWithText("Save schedule").performScrollTo().performClick()
        compose.onNodeWithText("Back to Stations").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.onNodeWithText("Schedule · Scheduled (paused)").performScrollTo().performClick()
        compose.onNodeWithText("Resume schedule").assertExists()
        compose.onNodeWithText("Resume schedule").performScrollTo().performClick()
        compose.onNodeWithText("Pause schedule").assertExists()
    }

    @Test fun scheduleOnlyChangesModeAndQueuesEachRevision() = runBlocking {
        val dao = database.stations()
        val saved = StationProfile(name = "Drive", minutes = 45, instructions = "Traffic")
        dao.save(saved)
        val stale = saved.copy(name = "Unsaved name", minutes = 10)
        for ((index, mode) in listOf(StationMode.SCHEDULED, StationMode.PAUSED, StationMode.MANUAL).withIndex()) {
            dao.saveSchedule(stale, mode)
            val record = dao.pending().single()
            assertEquals(saved.copy(mode = mode), record.decode())
            assertEquals(index.toLong() + 2, record.revision)
        }
    }

    @Test fun savedSegmentOrderTimesAndCuesSurviveReopening() = runBlocking {
        val context = compose.activity.applicationContext
        val dao = database.stations()
        val defaults = StationProfile(name = "Segment settings")
        val first = StationSegment(name = "World", randomPosition = true, durationSeconds = 90, cue = "bundled:international")
        val second = StationSegment(name = "Business", durationSeconds = 120, cue = "bundled:finance")
        val profile = defaults.copy(segments = listOf(
            defaults.segments.first(), second, first, defaults.segments.last()
        ))
        dao.save(profile)
        val reopened = dao.pending().first { it.id == profile.id }.decode()
        assertEquals(profile, reopened)
        dao.saveLineup(reopened.withSegment(first.copy(randomPosition = false)))
        val fixed = dao.pending().first { it.id == profile.id }.decode()
        assertTrue(fixed.segments.none { it.randomPosition })
        assertEquals(listOf("Opening", "Business", "World", "Closing"), fixed.segments.map { it.name })
        bundledCues.forEach { cue ->
            context.assets.openFd("station-cues/$cue.mp3").use { assertTrue(it.length > 0) }
        }
    }

    @Test fun lineupSavePreservesProfileFieldsAndQueuesBackup() = runBlocking {
        val dao = database.stations()
        val original = StationProfile(name = "Traffic", instructions = "Cover local roads", icon = "🚗")
        dao.save(original)
        val traffic = StationSegment(name = "Traffic", randomPosition = true)
        val edited = original.withSegment(traffic).copy(name = "Unsaved rename")
        dao.saveLineup(edited)
        val record = dao.pending().single()
        assertEquals(original.withSegment(traffic), record.decode())
        assertEquals(2L, record.revision)
        assertEquals("Backup pending", record.backup)
    }

    @Test fun segmentSavePersistsOnlyTheSelectedSegmentAndQueuesBackup() = runBlocking {
        val dao = database.stations()
        val original = StationProfile(name = "Local")
        dao.save(original)
        val unsaved = original.copy(name = "Uncommitted name")
        val traffic = StationSegment(name = "Traffic", durationSeconds = 90, enabled = false,
            instructions = "Cover bridge closures.")
        dao.saveSegment(unsaved, traffic)
        val reopened = dao.find(original.id)!!.decode()
        assertEquals(original.withSegment(traffic), reopened)
        assertEquals(traffic, reopened.segments[1])
        assertEquals(1, reopened.segments.count { !it.enabled })
        val renamed = traffic.copy(name = "Roads")
        dao.saveSegment(unsaved, renamed)
        assertEquals(original.withSegment(renamed), dao.pending().single().decode())
        val legacy = original.json()
        legacy.getJSONArray("segments").getJSONObject(0).apply {
            remove("enabled")
            remove("instructions")
            put("weight", 1)
        }
        assertEquals(original, StationProfile.from(legacy))
        for (endpoint in listOf(original.segments.first(), original.segments.last())) {
            val invalid = reopened.withSegment(endpoint.copy(enabled = false))
            assertNotNull(invalid.validationError())
        }
    }

    @Test fun importingAudioPreservesClipBytes() = runBlocking {
        val context = compose.activity.applicationContext
        val input = File.createTempFile("cue", ".mp3", context.cacheDir)
        try {
            context.assets.open("station-cues/detour.mp3").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            val cue = importStationCue(context, Uri.fromFile(input))
            val saved = importedCueFile(context, cue)
            try {
                assertArrayEquals(input.readBytes(), saved.readBytes())
            } finally {
                saved.delete()
            }
        } finally {
            input.delete()
        }
    }

    @Test fun backupStatusCannotMarkARevisionThatChangedAndCopyHasNewIds() = runBlocking {
        val dao = database.stations()
        val original = StationProfile(name = "Original", mode = StationMode.PAUSED)
        dao.save(original)
        val first = dao.revision(original.id)!!
        val changed = original.copy(name = "Changed")
        dao.save(changed)
        dao.mark(original.id, first, "Backed up")
        assertEquals(changed, dao.pending().first { it.id == original.id }.decode())

        val copy = changed.duplicate()
        assertNotEquals(changed.id, copy.id)
        assertEquals(changed.mode, copy.mode)
        assertEquals(changed.segments.map { it.name }, copy.segments.map { it.name })
        assertTrue(changed.segments.map { it.id }.intersect(copy.segments.map { it.id }.toSet()).isEmpty())

        val tooMany = changed.copy(minutes = 5, segments = listOf(
            changed.segments.first(), StationSegment(name = "First"), StationSegment(name = "Second"),
            changed.segments.last()
        ))
        assertNotNull(tooMany.validationError())
    }
}
