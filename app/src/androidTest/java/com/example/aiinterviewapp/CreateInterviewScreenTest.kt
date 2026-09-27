package com.example.aiinterviewapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.aiinterviewapp.ui.screens.create.CreateInterviewScreen
import com.example.aiinterviewapp.ui.theme.AIInterviewAppTheme
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the interview setup screen.
 *
 * NOTE: These run on a device/emulator via connectedAndroidTest and are
 * compiled (compileDebugAndroidTestKotlin) but NOT executed in CI without
 * a connected device.
 */
class CreateInterviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startSessionButtonStartsInterviewWithDefaults() {
        var started = false
        var capturedCount = -1
        composeRule.setContent {
            AIInterviewAppTheme {
                CreateInterviewScreen(
                    onBack = {},
                    onStartInterview = { _, _, _, _, count ->
                        started = true
                        capturedCount = count
                    }
                )
            }
        }

        composeRule.onNodeWithText("Start AI Session").performClick()

        composeRule.runOnIdle {
            org.junit.Assert.assertTrue("started should be true", started)
            org.junit.Assert.assertEquals("count should default to 5", 5, capturedCount)
        }
    }

    @Test
    fun roleTextFieldAcceptsUserInput() {
        composeRule.setContent {
            AIInterviewAppTheme {
                CreateInterviewScreen(
                    onBack = {},
                    onStartInterview = { _, _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithTag("role_field").performTextReplacement("iOS Developer")

        composeRule.onNodeWithTag("role_field").assertIsDisplayed()
    }
}