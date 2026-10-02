package io.github.zhzy0077.katadroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.ui.AppPreferences
import io.github.zhzy0077.katadroid.ui.record.RecordViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EnglishUiTest {
    @get:Rule(order = 0) val locale = AppLocaleRule("en")
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test fun pausedAutomaticTurnOffersResumeInEnglish() {
        lateinit var document: RecordViewModel
        ui.runOnIdle {
            document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false, autoBlack = true, maxVisits = 1))
            document.record.newGame()
            document.record.pauseAutomatic()
        }
        ui.onNodeWithTag("turn-label", useUnmergedTree = true).assertTextEquals("Auto paused")
        ui.onNodeWithText("Resume auto").assertIsDisplayed()
        ui.onNodeWithTag("resume-automatic").assertContentDescriptionEquals("Resume automatic moves").performClick()
        ui.waitUntil(30000) { document.record.position.moves.size == 1 }
        assertTrue(document.preferences.engineEnabled)
        ui.runOnIdle { document.updatePreferences(AppPreferences(engineEnabled = false)); document.record.reset() }
    }

    @Test fun englishSettingsApplyAsOneDraftAndGameActionsLiveOnlyInTheMenu() {
        lateinit var document: RecordViewModel
        ui.runOnIdle {
            document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.applyRules(GoRules.CHINESE, 7.5f)
            document.record.newGame()
            assertTrue(document.record.play(60))
        }
        ui.onNodeWithContentDescription("More options").performClick()
        ui.onNodeWithText("Import SGF").assertIsDisplayed()
        ui.onNodeWithText("Export SGF").assertIsDisplayed()
        ui.onNodeWithTag("menu-settings").performClick()
        ui.onNodeWithTag("import-sgf").assertDoesNotExist()
        ui.onNodeWithTag("export-sgf").assertDoesNotExist()
        ui.onNodeWithText("Replay").assertDoesNotExist()
        ui.onNodeWithTag("apply-settings").assertIsNotEnabled()
        ui.onNodeWithTag("search-limit-input").performTextReplacement("0")
        ui.onNodeWithTag("auto-black").performScrollTo().performClick()
        ui.onNodeWithTag("apply-settings").assertIsNotEnabled()
        assertFalse(document.preferences.autoBlack)
        ui.onNodeWithTag("search-limit-input").performScrollTo().performTextReplacement("123")
        ui.onNodeWithTag("rule-korean").performScrollTo().performClick()
        ui.onNodeWithTag("komi-input").performTextReplacement("-3.5")
        ui.onNodeWithTag("touch-offset-60").performScrollTo().performClick()
        ui.activityRule.scenario.recreate() // Draft survives rotation/recreation without being committed.
        assertEquals(500, document.preferences.maxVisits)
        assertEquals(GoRules.CHINESE, document.record.rules)
        assertEquals(0, document.preferences.touchOffsetPx)
        ui.onNodeWithTag("apply-settings").performClick()
        ui.waitUntil(5000) { !document.busy && document.preferences.maxVisits == 123 }
        assertTrue(document.preferences.autoBlack)
        assertEquals(GoRules.KOREAN, document.record.rules)
        assertEquals(-3.5f, document.record.komi, 0f)
        assertEquals(60, document.preferences.touchOffsetPx)
        ui.onNodeWithTag("apply-settings").assertIsNotEnabled()
        ui.onNodeWithTag("open-engines").performScrollTo().performClick()
        ui.onNodeWithText("Engines & models").assertIsDisplayed()
        ui.onNodeWithTag("backend-AUTO").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("start-benchmark").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("More options").performClick()
        ui.onNodeWithTag("menu-clear-board").performClick()
        ui.onNodeWithTag("confirm-clear-board").performClick()
        assertTrue(document.record.position.moves.isEmpty())
        assertEquals(GoRules.KOREAN, document.record.rules)
        assertEquals(-3.5f, document.record.komi, 0f)
        ui.runOnIdle {
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.applyRules(GoRules.CHINESE, 7.5f)
            document.record.reset()
        }
    }
}
