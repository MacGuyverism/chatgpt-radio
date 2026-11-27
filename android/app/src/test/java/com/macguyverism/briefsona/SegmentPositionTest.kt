package com.macguyverism.briefsona

import org.junit.Assert.*
import org.junit.Test

class SegmentPositionTest {
    @Test fun newSegmentsAreFixedAndSavedModesFollowTheSegment() {
        val fixed = StationSegment(name = "Local")
        val random = StationSegment(name = "Weather", randomPosition = true)
        val station = StationProfile(name = "Drive").withSegment(fixed).withSegment(random)
        val reopened = StationProfile.from(station.json())
        assertEquals(station, reopened)
        assertEquals(listOf(false, false, true, false), reopened.segments.map { it.randomPosition })
        val copy = reopened.duplicate()
        assertEquals(reopened.segments.map { it.randomPosition }, copy.segments.map { it.randomPosition })
        assertFalse(reopened.withSegment(random.copy(randomPosition = false)).segments.any { it.randomPosition })
    }

    @Test fun migratesLegacyStationChoiceWhilePinningEndpoints() {
        val station = StationProfile(name = "Drive").withSegment(StationSegment(name = "Local"))
        for (legacy in listOf(false, true)) {
            val json = station.json().put("random", legacy)
            val rows = json.getJSONArray("segments")
            for (i in 0 until rows.length()) rows.getJSONObject(i).remove("random_position")
            val reopened = StationProfile.from(json)
            assertEquals(listOf(false, legacy, false), reopened.segments.map { it.randomPosition })
            assertEquals(reopened, StationProfile.from(reopened.json()))
        }
    }
}
