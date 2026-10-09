package com.ichi2.anki.wearos.wear

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [TypeAnswerField] is the review front's type-in input: placeholder until focused/typed, and
 * every keystroke reaches [onValueChange] (the Activity diffs it against the expected answer).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeAnswerFieldTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun placeholderClearsAndTypedTextFlowsBack() {
    var value = ""
    composeRule.setContent { MaterialTheme { TypeAnswerField(value = value, onValueChange = { value = it }) } }
    composeRule.onNodeWithText("Type answer").assertExists()
    // Tapping the placeholder focuses the field; the IME-less test injects keystrokes directly.
    composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
    composeRule.onNode(hasSetTextAction()).performTextInput("paris")
    composeRule.waitForIdle()
    assertEquals("paris", value)
  }
}
