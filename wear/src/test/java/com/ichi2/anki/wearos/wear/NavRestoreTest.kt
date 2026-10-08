package com.ichi2.anki.wearos.wear

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The watch's screen stack survives process death through the ViewModel's saved state. */
@RunWith(RobolectricTestRunner::class)
class NavRestoreTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  private fun restored(vararg screens: String) =
    WatchViewModel(
        app,
        SavedStateHandle(
          mapOf("nav" to Bundle().apply { putStringArrayList("screens", arrayListOf(*screens)) })
        ),
      )
      .backStack
      .toList()

  @Test
  fun screensRoundTripThroughTheirEncoding() {
    val screens =
      listOf(
        WatchScreen.Decks(""),
        WatchScreen.Decks("Lang::JP"),
        WatchScreen.Settings,
        WatchScreen.Session,
        WatchScreen.Review,
      )
    assertEquals(screens, screens.map(::encodeScreen).map(::decodeScreen))
  }

  @Test
  fun freshStartIsTheRootPicker() {
    assertEquals(listOf(WatchScreen.Decks("")), WatchViewModel(app, SavedStateHandle()).backStack)
  }

  @Test
  fun restoresFoldersAndSettings() {
    assertEquals(
      listOf(WatchScreen.Decks(""), WatchScreen.Decks("Lang"), WatchScreen.Settings),
      restored("decks:", "decks:Lang", "settings"),
    )
  }

  @Test
  fun sessionScreensWithoutADeckFallBackToThePicker() {
    assertEquals(
      listOf(WatchScreen.Decks(""), WatchScreen.Decks("Lang")),
      restored("decks:", "decks:Lang", "session", "review"),
    )
  }
}
