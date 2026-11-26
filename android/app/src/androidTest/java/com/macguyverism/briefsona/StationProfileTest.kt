package com.macguyverism.briefsona

import android.net.Uri
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
        compose.onNodeWithText("+ Add Station").performClick()
        compose.onNodeWithText("Station name").performTextInput(name)
        compose.onNodeWithText("Scheduled").performClick()
        compose.onNodeWithText("Pause schedule").performScrollTo().performClick()
        compose.onNodeWithText("Save station").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }

        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performScrollTo().performClick()
        compose.onNodeWithText("Resume schedule").assertExists()
        compose.onNodeWithText("Resume schedule").performScrollTo().performClick()
        compose.onNodeWithText("Pause schedule").assertExists()
    }

    @Test fun savedSegmentOrderWeightsAndCuesSurviveReopening() = runBlocking {
        val context = compose.activity.applicationContext
        val dao = database.stations()
        val defaults = StationProfile(name = "Segment settings")
        val first = StationSegment(name = "World", weight = 3, cue = "bundled:international")
        val second = StationSegment(name = "Business", weight = 2, cue = "bundled:finance")
        val profile = defaults.copy(segments = listOf(
            defaults.segments.first(), second, first, defaults.segments.last()
        ), random = true)
        dao.save(profile)
        val reopened = dao.pending().first { it.id == profile.id }.decode()
        assertEquals(profile, reopened)
        dao.save(reopened.copy(random = false))
        val fixed = dao.pending().first { it.id == profile.id }.decode()
        assertFalse(fixed.random)
        assertEquals(listOf("Opening", "Business", "World", "Closing"), fixed.segments.map { it.name })
        bundledCues.forEach { cue ->
            context.assets.openFd("station-cues/$cue.mp3").use { assertTrue(it.length > 0) }
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
        val original = StationProfile(name = "Original", mode = StationMode.PAUSED, random = true)
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
