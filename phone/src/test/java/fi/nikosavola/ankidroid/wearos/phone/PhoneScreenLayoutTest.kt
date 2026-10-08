package fi.nikosavola.ankidroid.wearos.phone

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screen at the window sizes the audit flagged: Sync and search must stay reachable and the
 * deck list must scroll, whether the window is a small portrait phone, a landscape phone (two
 * panes, little height) or a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenLayoutTest {
  @get:Rule val composeRule = createComposeRule()

  private val decks = (1..40).map { DeckId(it.toLong()) to "Deck %02d".format(it) }

  private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
    PhoneViewModel(ApplicationProvider.getApplicationContext<Application>(), handle).apply {
      showDecks(decks + (DeckId(99L) to "Japani::Kaishi 1.5k"), emptyMap())
      cardAccess = CardAccess.GRANTED
      syncEnabled = true
    }

  private fun show(vm: PhoneViewModel, onSync: () -> Unit = {}) {
    composeRule.setContent { AnkiPhoneTheme { PhoneScreen(vm, PhoneActions(onSync = onSync)) } }
  }

  private fun assertSyncAndListReachable(onSyncCount: () -> Int) {
    composeRule.onNodeWithTag(SYNC_FAB_TAG).assertIsDisplayed().performClick()
    assertEquals(1, onSyncCount())
    composeRule.onNodeWithText("Search decks").assertIsDisplayed()
    // The last deck is reachable by scrolling the one list, not hidden behind fixed content.
    composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("deck:Deck 40")
    composeRule.onNodeWithText("Deck 40").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w320dp-h533dp-port")
  fun smallPortraitPhone_syncAndDecksReachable() {
    var synced = 0
    show(viewModel()) { synced++ }
    composeRule.onNodeWithTag(SUPPORTING_PANE_TAG).assertDoesNotExist()
    assertSyncAndListReachable { synced }
  }

  @Test
  @Config(qualifiers = "w800dp-h360dp-land")
  fun landscapePhone_twoPanes_syncAndDecksReachable() {
    var synced = 0
    show(viewModel()) { synced++ }
    composeRule.onNodeWithTag(SUPPORTING_PANE_TAG).assertExists()
    assertSyncAndListReachable { synced }
  }

  @Test
  @Config(qualifiers = "w1280dp-h800dp-land")
  fun tabletLandscape_showsSupportingPane() {
    var synced = 0
    show(viewModel()) { synced++ }
    composeRule.onNodeWithTag(SUPPORTING_PANE_TAG).assertIsDisplayed()
    composeRule.onNodeWithText("Cards due to sync").assertIsDisplayed()
    assertSyncAndListReachable { synced }
  }

  @Test
  @Config(qualifiers = "w320dp-h533dp-port")
  fun search_keepsSpacesBetweenWords_andOpensMatchingFolders() {
    val vm = viewModel()
    show(vm)
    composeRule.onNodeWithText("Search decks").performTextInput("Kaishi 1.5k")
    assertEquals("Kaishi 1.5k", vm.deckFilter)
    // The match sits inside the collapsed "Japani" folder; a search opens it.
    composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("deck:Japani::Kaishi 1.5k")
    composeRule
      .onNode(hasText("Kaishi 1.5k") and !hasSetTextAction(), useUnmergedTree = true)
      .assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "w320dp-h533dp-port")
  fun topLevelDecksAndFolders_shareLeftEdge() {
    show(viewModel())
    composeRule.onNode(hasScrollToKeyAction()).performScrollToKey("deck:Japani")
    val deck = composeRule.onNode(hasText("Deck 40") and hasClickAction()).getUnclippedBoundsInRoot()
    val folder = composeRule.onNode(hasText("Japani") and hasClickAction()).getUnclippedBoundsInRoot()
    assertEquals(folder.left, deck.left)
  }

  @Test
  fun savedState_restoresSearchFoldersAndDialog() {
    val handle = SavedStateHandle()
    viewModel(handle).apply {
      updateDeckFilter("deck ")
      toggleFolder("Japani")
      openDialog(PhoneDialog.SETTINGS)
    }
    val restored = viewModel(handle)
    assertEquals("deck ", restored.deckFilter)
    assertEquals(setOf("Japani"), restored.expandedFolders)
    assertEquals(PhoneDialog.SETTINGS, restored.activeDialog)
  }
}
