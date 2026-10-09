package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
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

  // A {{type:Field}} card: the phone filled the back's typeans element with the expected answer,
  // which is both what the user should type against and the proof this card wants typing at all.
  val typeExpected = remember(card.id, card.ord) { typeAnswerExpected(card.back) }
  var typed by remember(card.id, card.ord) { mutableStateOf("") }

  // What the user typed, diffed against the expected answer by the core once revealed. Nothing
  // typed keeps the plain expected answer AnkiDroid shows.
  val typedDiff =
    remember(answerBack, typed, isRevealed) {
      if (!isRevealed || typed.isEmpty()) null else compareTypedAnswer(answerBack, typed)
    }

  // Rich HTML for display; plain text for TTS.
  val displayFront = remember(card.front) { htmlForDisplay(card.front) }
  val displayBack = remember(typedDiff) { htmlForDisplay(typedDiff ?: answerBack) }
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

  // The field recreates unfocused per card; without this a stale focused flag would keep the
  // swipe/back gesture off for the next card's front.
  DisposableEffect(card.id, card.ord) {
    onDispose { typeAnswerFocused = false }
  }

  // Hide-clock: override the app's TimeText with an empty one for the review card only (null keeps
  // the default clock from AppScaffold).
  val emptyTimeText: @Composable () -> Unit = {}
  val reviewTimeText = if (hideClock) emptyTimeText else null
  PrefetchUpcomingImages(session, card.id)
  ScreenScaffold(scrollState = scrollState, timeText = reviewTimeText) {
    if (!isRevealed) {
      ReviewFront(
        session,
        card,
        displayFront,
        cleanFront,
        curvedStyle,
        scrollState,
        typeExpected = typeExpected,
        typed = typed,
        onTyped = { typed = it },
        onTypeFocused = { typeAnswerFocused = it },
      ) {
        session.revealAnswer()
        isRevealed = true
      }
    } else {
      ReviewAnswer(
        session,
        card,
        displayFront,
        displayBack,
        cleanFront,
        cleanBack,
        scrollState,
        cardStartTime,
        gradeEnabled = !isAnswering,
        curvedStyle = curvedStyle,
        // The back bitmap shows the nothing-typed answer; a diff must not sit under it.
        suppressBackImage = typedDiff != null,
      ) { ease, duration ->
        if (!isAnswering) {
          isAnswering = true
          // Confirm the grade with a short haptic tick (eyes-free / glanceable feedback).
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          // Answers just buffer locally here; they sync on the Finished screen, the deck-screen
          // "Sync N reviews" action, or background sync, not after every grade (too noisy).
          answerCardSafely(session, ease, duration) { isAnswering = false }
        }
      }
    }
  }
}

/**
 * The type-in answer field. Wear's Material has no text input component, so this is a plain
 * [BasicTextField] whose focus opens the system IME (voice or keyboard); it consumes its own taps,
 * so typing never triggers the surrounding tap-to-reveal. The placeholder clears on focus.
 */
@Composable
internal fun TypeAnswerField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  onFocusedChanged: (Boolean) -> Unit = {},
) {
  var focused by remember { mutableStateOf(false) }
  val style = MaterialTheme.typography.bodyMedium
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
    cursorBrush =
      Brush.verticalGradient(
        0f to MaterialTheme.colorScheme.primary,
        1f to MaterialTheme.colorScheme.primary,
      ),
    decorationBox = { inner ->
      if (value.isEmpty() && !focused) {
        Text(
          text = "Type answer",
          style = style,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        inner()
      }
    },
    modifier =
      modifier.fillMaxWidth(0.9f).onFocusChanged {
        focused = it.isFocused
        onFocusedChanged(it.isFocused)
      },
  )
}

/** Undo the previous grade: a small glyph with a full 48dp touch target around it. */
@Composable
internal fun UndoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier.size(48.dp).clickable(onClickLabel = "Undo last grade", role = Role.Button) {
        onClick()
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_undo),
      contentDescription = "Undo",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(16.dp),
    )
  }
}

/** One card side: the phone-rendered image read out as [spokenText], else the HTML fallback. */
@Composable
internal fun CardSide(
  image: SideImage,
  fallbackHtml: String,
  spokenText: String,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  modifier: Modifier = Modifier,
) {
  when (image) {
    is SideImage.Ready ->
      Image(
        bitmap = image.bitmap,
        contentDescription = spokenText,
        modifier = modifier.fillMaxWidth(),
        contentScale = ContentScale.Fit,
      )
    SideImage.None ->
      HtmlText(html = fallbackHtml, fontScale = fontScale, cardFont = cardFont, modifier = modifier)
    SideImage.Loading -> Unit
  }
}
