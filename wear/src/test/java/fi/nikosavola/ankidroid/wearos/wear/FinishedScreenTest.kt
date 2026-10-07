package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import uniffi.wearos_core.SessionSummary

/**
 * Render tests for FinishedScreenContent, extracted out of MainActivity so it can be driven here.
 * Covers the recap (reviews/ease-split/streak), the empty-session case, the outstanding count, and
 * that the edge button invokes the sync-and-back callback the Activity wires up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinishedScreenTest {
  @get:Rule val composeRule = createComposeRule()

  private fun summary(total: Int, again: Int, hard: Int, good: Int, easy: Int) =
    SessionSummary(
      total = total,
      again = again,
      hard = hard,
      good = good,
      easy = easy,
      totalTimeMs = 12_000L,
    )

  @Test
  fun showsRecapAndStreak_andFiresSyncOnEdgeButton() {
    var synced = 0
    composeRule.setContent {
      MaterialTheme {
        FinishedScreenContent(
          summary = summary(total = 5, again = 1, hard = 0, good = 3, easy = 1),
          reviewedToday = 7,
          outstandingCount = 2,
          transformSpec = rememberEdgeMorphSpec(),
          onSyncAndBack = { synced++ },
        )
      }
    }

    composeRule.onNodeWithText("Nice Job! 🎉").assertIsDisplayed()
    composeRule.onNodeWithText("REVIEWS").assertIsDisplayed()
    composeRule.onNodeWithText("5").assertIsDisplayed()
    composeRule.onNodeWithText("Again 1 · Hard 0 · Good 3 · Easy 1").assertIsDisplayed()
    composeRule.onNodeWithText("Today: 7 🔥").assertIsDisplayed()
    composeRule.onNodeWithText("Outstanding reviews: 2").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Sync and back to decks").performClick()
    assertEquals(1, synced)
  }

  @Test
  fun showsLearningDueLaterLine_whenTheDeckHasCardsComingUpToday() {
    composeRule.setContent {
      MaterialTheme {
        FinishedScreenContent(
          summary = summary(total = 3, again = 0, hard = 0, good = 3, easy = 0),
          reviewedToday = 3,
          outstandingCount = 0,
          transformSpec = rememberEdgeMorphSpec(),
          onSyncAndBack = {},
          learningLater = 2,
          learningDueInMinutes = 5,
        )
      }
    }

    composeRule.onNodeWithText("2 learning cards due in 5 min").assertIsDisplayed()
  }

  @Test
  fun emptySession_hidesRecapButStillShowsHeaderAndOutstanding() {
    composeRule.setContent {
      MaterialTheme {
        FinishedScreenContent(
          summary = summary(total = 0, again = 0, hard = 0, good = 0, easy = 0),
          reviewedToday = 0,
          outstandingCount = 0,
          transformSpec = rememberEdgeMorphSpec(),
          onSyncAndBack = {},
        )
      }
    }

    composeRule.onNodeWithText("Nice Job! 🎉").assertIsDisplayed()
    composeRule.onNodeWithText("Outstanding reviews: 0").assertIsDisplayed()
    // No reviews this session -> no recap card and no streak line.
    composeRule.onNodeWithText("REVIEWS").assertDoesNotExist()
    composeRule.onNodeWithText("Today: 0 🔥").assertDoesNotExist()
  }
}
