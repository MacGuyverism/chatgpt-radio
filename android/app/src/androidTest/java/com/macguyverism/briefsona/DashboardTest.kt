package com.macguyverism.briefsona

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
