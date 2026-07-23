package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import uniffi.wearos_core.*

@Composable
internal fun MainActivity.ReviewCardScreen(session: ReviewSession, card: WearosCard) {
  val cardStartTime = remember(card.id, card.ord) { System.currentTimeMillis() }
  var isRevealed by remember(card.id, card.ord) { mutableStateOf(session.isRevealed()) }
  var isAnswering by remember(card.id, card.ord) { mutableStateOf(false) }
  val scrollState = rememberScrollState()
  val haptics = LocalHapticFeedback.current

  val labelStyle = MaterialTheme.typography.labelSmall
  val curvedStyle =
    remember(labelStyle) {
      androidx.wear.compose.foundation.CurvedTextStyle(labelStyle.copy(fontSize = 11.sp))
    }

  // Anki answers embed the question followed by <hr id=answer>; the Rust core returns just the
  // part after it so the answer view doesn't duplicate the front (whole back if no marker).
  val answerBack = remember(card.back) { answerHtml(card.back) }

  // Rich HTML for display; plain text for TTS.
  val displayFront = remember(card.front) { htmlForDisplay(card.front) }
  val displayBack = remember(answerBack) { htmlForDisplay(answerBack) }
  val cleanFront = remember(card.front) { cleanHtml(card.front) }
  val cleanBack = remember(answerBack) { cleanHtml(answerBack) }

  // TTS voice effects triggered dynamically via state change
  LaunchedEffect(card.id, card.ord) { speak(cleanFront) }
  LaunchedEffect(isRevealed) {
    if (isRevealed) {
      speak(cleanBack)
    }
  }

  // Optionally hold the screen on for the duration of the review (opt-in; battery cost). The flag
  // is cleared when we leave the review screen.
  if (keepScreenOn) {
    DisposableEffect(Unit) {
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
  }

  // Hide-clock: override the app's TimeText with an empty one for the review card only (null keeps
  // the default clock from AppScaffold).
  val emptyTimeText: @Composable () -> Unit = {}
  val reviewTimeText = if (hideClock) emptyTimeText else null
  ScreenScaffold(scrollState = scrollState, timeText = reviewTimeText) {
    if (!isRevealed) {
      ReviewFront(session, card, displayFront, curvedStyle, scrollState) {
        session.revealAnswer()
        isRevealed = true
      }
    } else {
      ReviewAnswer(
        session,
        card,
        displayFront,
        displayBack,
        scrollState,
        cardStartTime,
        gradeEnabled = !isAnswering,
        curvedStyle = curvedStyle,
      ) { ease, duration ->
        if (!isAnswering) {
          isAnswering = true
          // Confirm the grade with a short haptic tick (eyes-free / glanceable feedback).
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          // Answers just buffer locally here; they sync on the Finished screen, the deck-screen
          // "Sync N reviews" action, or background sync — not after every grade (too noisy).
          answerCardSafely(session, ease, duration) { isAnswering = false }
        }
      }
    }
  }
}
