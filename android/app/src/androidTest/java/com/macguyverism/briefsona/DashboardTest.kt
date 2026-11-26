package com.macguyverism.briefsona

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.content.pm.ActivityInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationAndAppearance() {
        compose.onNodeWithText("RADIO").assertIsDisplayed()
        compose.onNodeWithText("Stations").performClick()
        compose.onNodeWithText("Retrieve broadcast").assertExists()
        compose.onNodeWithText("Settings").performClick()
        for (background in listOf("Charcoal", "Midnight Blue", "Dark Espresso")) {
            compose.onNodeWithText(background).performClick()
            for (accent in listOf("Amber", "Ice Blue", "Sport Red", "Emerald", "Purple", "White")) {
                compose.onNodeWithContentDescription(accent).performClick()
            }
        }
        compose.onNodeWithText("Radio").performClick()
        compose.onNodeWithText("Morning Commute").assertIsDisplayed()
    }

    @Test fun offlinePlaybackAndListeningPosition() {
        val context = compose.activity.applicationContext
        val dao = Library.get(context).episodes()
        val samples = ByteArray(8_000 * 2 * 30)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples.size)
        }
        val file = File(context.filesDir, "offline-test.wav")
        file.outputStream().use { it.write(header.array()); it.write(samples) }
        val entry = Episode("test-local", LocalDate.now().toString(), "", "", 30_000, file.absolutePath)
        runBlocking { dao.insert(entry) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().any()
        }
        compose.onNodeWithContentDescription("Play").performScrollTo().performClick()
        compose.waitUntil(12_000) { runBlocking { (dao.find(entry.id)?.position ?: 0) > 0 } }
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().any()
        }
        compose.onNodeWithContentDescription("Pause").performScrollTo().performClick()
        assertTrue(runBlocking { (dao.find(entry.id)?.position ?: 0) > 0 })
    }
}
