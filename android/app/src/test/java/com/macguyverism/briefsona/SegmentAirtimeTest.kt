package com.macguyverism.briefsona

import org.junit.Assert.*
import org.junit.Test

class SegmentAirtimeTest {
    private val station = StationProfile(name = "Drive", minutes = 15)
    private val news = StationSegment(name = "News")
    private val traffic = StationSegment(name = "Traffic")

    @Test fun starterInstructionsCanBeEditedOrCleared() {
        assertTrue(station.instructions.isNotBlank())
        for (prompt in listOf(station.instructions, "Focus on local transit.", "")) {
            val edited = station.copy(instructions = prompt)
            assertEquals(prompt, StationProfile.from(edited.json()).instructions)
            assertEquals(prompt, edited.duplicate().instructions)
        }
        val legacy = station.json().apply { remove("instructions") }
        assertEquals("", StationProfile.from(legacy).instructions)
    }

    @Test fun sharesRemainingTimeAndRespectsEdits() {
        val empty = station.airtime()
        assertEquals(840, empty.remaining)
        assertEquals(listOf(30, 30), station.segments.map { empty.estimates[it.id] })
        val lineup = station.withSegment(news).withSegment(traffic)
        assertEquals(420, lineup.airtime().estimates.getValue(news.id))
        val longerIntro = lineup.withSegment(lineup.segments.first().copy(durationSeconds = 61))
        val split = longerIntro.airtime()
        assertEquals(809, split.remaining)
        assertEquals(405, split.estimates.getValue(news.id))
        assertEquals(404, split.estimates.getValue(traffic.id))
        val fixed = longerIntro.withSegment(news.copy(durationSeconds = 120))
        assertEquals(689, fixed.airtime().estimates.getValue(traffic.id))
        val disabled = fixed.withSegment(news.copy(enabled = false, durationSeconds = 120))
        assertEquals(809, disabled.airtime().estimates.getValue(traffic.id))
        assertFalse(disabled.airtime().estimates.containsKey(news.id))
        assertEquals(fixed, StationProfile.from(fixed.json()))
        assertNull(fixed.validationError())
    }

    @Test fun rejectsOverrunsAndReservesTimeForAutomaticSegments() {
        val lineup = station.withSegment(news).withSegment(traffic)
        for (seconds in listOf(0, 840, 841)) {
            assertNotNull(lineup.withSegment(news.copy(durationSeconds = seconds)).validationError())
        }
        assertNull(lineup.withSegment(news.copy(durationSeconds = 839)).validationError())
    }

    @Test fun olderUnequalWeightsBecomeTimeEstimatesAndEqualWeightsBecomeAutomatic() {
        val lineup = station.withSegment(news).withSegment(traffic)
        val json = lineup.json()
        val rows = json.getJSONArray("segments")
        for (i in 0 until rows.length()) {
            rows.getJSONObject(i).apply { remove("duration_seconds"); put("weight", 1) }
        }
        assertEquals(lineup, StationProfile.from(json))
        rows.getJSONObject(1).put("weight", 1.5)
        val migrated = StationProfile.from(json)
        assertEquals(listOf(null, 504, 336, null), migrated.segments.map { it.durationSeconds })
        assertEquals(migrated, StationProfile.from(migrated.json()))
    }
}
