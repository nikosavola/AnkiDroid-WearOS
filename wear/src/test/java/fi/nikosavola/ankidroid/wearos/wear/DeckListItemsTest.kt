package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Render tests for the deck-picker rows extracted out of MainActivity. They now take state + an
 * onClick, so we can assert what the user sees (deck name, cached vs "Tap to download", the folder
 * subtitle, the stat label/number) and that a tap invokes the callback the Activity wires to
 * navigation - none of which was testable while these lived as Activity methods.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeckListItemsTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun deckCard_cached_showsReadyCountAndFiresOnClick() {
    var clicks = 0
    composeRule.setContent {
      MaterialTheme {
        DeckCard(
          label = "Kaishi 1.5k",
          dueNow = 5,
          modifier = Modifier.testTag("deck"),
          onClick = { clicks++ },
        )
      }
    }
    composeRule.onNodeWithText("Kaishi 1.5k").assertIsDisplayed()
    composeRule.onNodeWithText("5 cards ready").assertIsDisplayed()
    composeRule.onNodeWithTag("deck").performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun deckCard_cached_oneCard_usesSingular() {
    composeRule.setContent {
      MaterialTheme {
        DeckCard(label = "Kaishi 1.5k", dueNow = 1, modifier = Modifier, onClick = {})
      }
    }
    composeRule.onNodeWithText("1 card ready").assertIsDisplayed()
  }

  @Test
  fun deckCard_uncached_showsTapToDownload() {
    composeRule.setContent {
      MaterialTheme { DeckCard(label = "Yomitan", dueNow = null, modifier = Modifier, onClick = {}) }
    }
    composeRule.onNodeWithText("Yomitan").assertIsDisplayed()
    composeRule.onNodeWithText("Tap to download").assertIsDisplayed()
  }

  @Test
  fun folderCard_showsNameAndFolderSubtitle_andFiresOnClick() {
    var clicks = 0
    composeRule.setContent {
      MaterialTheme {
        FolderCard(name = "文法", modifier = Modifier.testTag("folder"), onClick = { clicks++ })
      }
    }
    composeRule.onNodeWithText("文法").assertIsDisplayed()
    composeRule.onNodeWithText("Folder").assertIsDisplayed()
    composeRule.onNodeWithTag("folder").performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun statNumeral_uppercasesLabelAndShowsValue() {
    composeRule.setContent {
      MaterialTheme { StatNumeral(label = "Due", value = "12", color = Color.Unspecified) }
    }
    composeRule.onNodeWithText("DUE").assertIsDisplayed()
    composeRule.onNodeWithText("12").assertIsDisplayed()
  }

  @Test
  fun deckCard_longPressFiresOnLongClick() {
    var longPresses = 0
    composeRule.setContent {
      MaterialTheme {
        DeckCard(
          label = "Kaishi 1.5k",
          dueNow = 5,
          modifier = Modifier.testTag("deck"),
          onClick = {},
          onLongClick = { longPresses++ },
        )
      }
    }
    composeRule.onNodeWithTag("deck").performTouchInput { longClick() }
    assertEquals(1, longPresses)
  }

  @Test
  fun folderCard_longPressFiresOnLongClick() {
    var longPresses = 0
    composeRule.setContent {
      MaterialTheme {
        FolderCard(
          name = "文法",
          modifier = Modifier.testTag("folder"),
          onClick = {},
          onLongClick = { longPresses++ },
        )
      }
    }
    composeRule.onNodeWithTag("folder").performTouchInput { longClick() }
    assertEquals(1, longPresses)
  }

  @Test
  fun deckRowMenu_showsTitleAndFiresThePickedAction() {
    var picked: String? = null
    composeRule.setContent {
      MaterialTheme {
        DeckRowMenu(
          title = "Kaishi 1.5k",
          actions =
            listOf(
              "Refresh from phone" to { picked = "refresh" },
              "Remove from watch" to { picked = "remove" },
            ),
          onDismissRequest = {},
        )
      }
    }
    composeRule.onNodeWithText("Kaishi 1.5k").assertIsDisplayed()
    composeRule.onNodeWithText("Refresh from phone").performClick()
    assertEquals("refresh", picked)
    composeRule.onNodeWithText("Remove from watch").performClick()
    assertEquals("remove", picked)
  }
}
