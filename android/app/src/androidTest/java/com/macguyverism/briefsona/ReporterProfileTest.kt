package com.macguyverism.briefsona

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReporterProfileTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun customizeCancelSaveAndReloadLegacyProfile() {
        val prefs = compose.activity.getSharedPreferences("reporter_profiles", Context.MODE_PRIVATE)
        val original = prefs.getString("samantha", null)
        val legacy = JSONObject().put("name", "Legacy reporter").put("custom_field", "preserved")
        prefs.edit().putString("samantha", legacy.toString()).commit()
        fun tap(label: String) { compose.onNodeWithText(label).performScrollTo().performClick() }
        try {
            compose.onNodeWithText("Stations").performClick()
            compose.onNodeWithContentDescription("Add station").performClick()
            tap("Reporter library")
            tap("Legacy reporter")
            tap("Choose and customize avatar")
            compose.onNodeWithContentDescription("Wear glasses").performScrollTo().performClick()
            tap("←  Reporter profile")
            tap("Choose and customize avatar")
            compose.onNodeWithContentDescription("Wear glasses").assertIsOff()
            compose.onNodeWithContentDescription("Wear glasses").performScrollTo().performClick()
            tap("Use appearance")
            assertEquals(legacy.toString(), prefs.getString("samantha", null))
            tap("Save reporter")
            val saved = JSONObject(prefs.getString("samantha", null)!!)
            assertEquals("preserved", saved.getString("custom_field"))
            assertTrue(saved.getJSONObject("appearance").getBoolean("glasses"))
            tap("Legacy reporter")
            tap("Choose and customize avatar")
            compose.onNodeWithContentDescription("Wear glasses").assertIsOn()
        } finally {
            prefs.edit().putString("samantha", original).commit()
        }
    }
}
