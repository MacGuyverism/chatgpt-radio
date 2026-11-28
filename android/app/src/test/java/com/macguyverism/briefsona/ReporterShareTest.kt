package com.macguyverism.briefsona

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReporterShareTest {
    @Test fun portableReporterRoundTripAndAuthentication() {
        val profile = JSONObject().put("name", "Reporter").put("voice", "nova")
            .put("speaking_speed", 1.15).put("markdown", "Custom instructions").put("Warmth", 75)
            .put("appearance", JSONObject().put("avatar_id", "avatar_011").put("hair_color", "#211D1B"))
        val bytes = sealReporter(profile, "shared passphrase")
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Custom instructions"))
        val imported = openReporter(bytes, "shared passphrase")
        for (key in profile.keys()) assertEquals(profile[key].toString(), imported[key].toString())
        assertTrue(runCatching { openReporter(bytes, "different passphrase") }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { openReporter(bytes, "shared passphrase") }.isFailure)
    }
}
