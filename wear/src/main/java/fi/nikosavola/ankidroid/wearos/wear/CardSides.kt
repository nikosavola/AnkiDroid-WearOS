package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.curvedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wearos_core.ReviewSession
import uniffi.wearos_core.WearosCard

/**
 * Session progress: a determinate ring hugging the round edge, filling clockwise in proportion to
 * cards graduated this session, with the N/M label curved along the top. Callers draw it inside
 * their single cached overlay layer, above the card body so an opaque card image can't cover it.
 * [progressTrigger] is the Activity's grade counter, so answering or undoing refreshes the ring.
 */
@Composable
internal fun ReviewProgress(session: ReviewSession, progressTrigger: Int, style: CurvedTextStyle) {
  // Native calls once per grade rather than on every draw of the ring.
  val fraction = remember(session, progressTrigger) { session.getProgressFraction() }
  val label = remember(session, progressTrigger) { session.getProgressString() }
  // Card text scrolled up fades out under the label rather than running through it.
  val background = MaterialTheme.colorScheme.background
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(44.dp)
        .background(Brush.verticalGradient(0.5f to background, 1f to Color.Transparent))
  )
  CircularProgressIndicator(
    progress = { fraction },
    modifier = Modifier.fillMaxSize().padding(2.dp),
  )
  TopCurvedProgress(label, style)
}

/**
 * The N/M session progress as curved text hugging the top arc (matching the curved answer hint +
 * grade buttons), inset radially so it clears the ScreenScaffold clock at the very top.
 */
@Composable
private fun TopCurvedProgress(text: String, style: CurvedTextStyle) {
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  // TalkBack otherwise reads the bare "N/M".
  val spoken = text.replace("/", " of ") + " done"
  CurvedLayout(
    modifier = Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = spoken },
    anchor = 270f,
    anchorType = AnchorType.Center,
  ) {
    curvedText(
      text = text,
      style = style,
      color = color,
      modifier = CurvedModifier.padding(outer = 22.dp, inner = 0.dp, before = 0.dp, after = 0.dp),
    )
  }
}

/**
 * Side inset for card text. A full-width line only clears a round display at the vertical center;
 * everywhere else its ends sit under the bezel curve, so text gets extra air there. Square
 * screens have flat edges and keep the width.
 */
@Composable
private fun cardSidePadding(): Dp =
  if (LocalConfiguration.current.isScreenRound) 20.dp else 14.dp

/**
 * Front of a review card: progress, an optional undo, the tappable card body, an optional
 * type-in field, and a reveal hint.
 */
@Composable
internal fun BoxScope.ReviewFront(
  session: ReviewSession,
  card: WearosCard,
  displayFront: String,
  spokenFront: String,
  curvedStyle: CurvedTextStyle,
  scrollState: ScrollState,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  showProgress: Boolean,
  progressTrigger: Int,
  onUndoLast: (ReviewSession) -> Unit,
  typeExpected: String?,
  typed: String,
  onTyped: (String) -> Unit,
  onTypeFocused: (Boolean) -> Unit,
  onReveal: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(scrollState)
        .rotaryScroll(scrollState)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClickLabel = "Show answer",
          role = Role.Button,
        ) {
          onReveal()
        }
        .padding(start = cardSidePadding(), end = cardSidePadding(), top = 40.dp, bottom = 34.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CardSideContent(
      cardId = card.id,
      side = "front",
      fallbackHtml = displayFront,
      spokenText = spokenFront,
      fontScale = fontScale,
      cardFont = cardFont,
      modifier = Modifier.fillMaxWidth(),
    )
    if (typeExpected != null) {
      Spacer(modifier = Modifier.height(10.dp))
      TypeAnswerField(value = typed, onValueChange = onTyped, onFocusedChanged = onTypeFocused)
    }
  }
  val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
  Box(modifier = Modifier.fillMaxSize().cachedLayer()) {
    if (showProgress) ReviewProgress(session, progressTrigger, curvedStyle)
    CurvedLayout(
      modifier = Modifier.fillMaxSize(),
      anchor = 90f,
      anchorType = AnchorType.Center,
      angularDirection = CurvedDirection.Angular.Reversed,
    ) {
      curvedText(
        text = if (typeExpected != null) "Type, then tap to check" else "Tap to show answer",
        style = curvedStyle,
        color = hintColor,
      )
    }
  }
  // Undo the previous grade, tucked just left of the progress text at the top. Drawn LAST so it
  // sits above the full-screen reveal-clickable Column, which would otherwise eat the tap. The
  // 48dp target is centered where the 16dp glyph always sat.
  if (session.canUndo()) {
    UndoButton(
      onClick = { onUndoLast(session) },
      modifier = Modifier.align(Alignment.TopCenter).offset(x = (-30).dp),
    )
  }
}

/** Revealed card: front + answer scrolling under the curved grade buttons. */
@Composable
internal fun BoxScope.ReviewAnswer(
  session: ReviewSession,
  card: WearosCard,
  displayFront: String,
  displayBack: String,
  spokenFront: String,
  spokenBack: String,
  scrollState: ScrollState,
  cardStartTime: Long,
  gradeEnabled: Boolean,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  curvedStyle: CurvedTextStyle,
  suppressBackImage: Boolean,
  coloredButtons: Boolean,
  showProgress: Boolean,
  progressTrigger: Int,
  onGrade: (ease: Int, durationMs: Long) -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(scrollState)
        .rotaryScroll(scrollState)
        // Extra bottom space so the answer can scroll past the overlay buttons and every line can
        // be brought fully into the clear above them.
        // Top inset clears the curved N/M progress label, which ends about 36dp down.
        .padding(start = cardSidePadding(), end = cardSidePadding(), top = 40.dp, bottom = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CardSideContent(
      cardId = card.id,
      side = "front",
      fallbackHtml = displayFront,
      spokenText = spokenFront,
      fontScale = fontScale,
      cardFont = cardFont,
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(
      modifier =
        Modifier.padding(vertical = 6.dp)
          .fillMaxWidth(0.5f)
          .height(1.dp)
          .background(Color.DarkGray)
    )
    CardSideContent(
      cardId = card.id,
      side = "back",
      fallbackHtml = displayBack,
      spokenText = spokenBack,
      fontScale = fontScale,
      cardFont = cardFont,
      forceNative = suppressBackImage,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  GradeEdgeButtons(
    card = card,
    enabled = gradeEnabled,
    colored = coloredButtons,
    chrome = { if (showProgress) ReviewProgress(session, progressTrigger, curvedStyle) },
  ) { ease ->
    onGrade(ease, System.currentTimeMillis() - cardStartTime)
  }
}

/**
 * Renders the phone-rendered bitmap for a card side if the watch has one, else the HTML. The
 * bitmap is decoded off the main thread; until it is ready (only on a cache miss, prefetching
 * usually covers it) the side draws nothing rather than flashing the HTML fallback. [spokenText]
 * is the side's plain text, which TalkBack reads for the image.
 */
@Composable
internal fun CardSideContent(
  cardId: Long,
  side: String,
  fallbackHtml: String,
  spokenText: String,
  fontScale: Float,
  cardFont: WatchSettings.CardFont,
  forceNative: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val widthPx = decodeWidthPx()
  // Keyed state rather than produceState, which would keep showing the previous card's image
  // until the new decode lands. forceNative skips a stale bitmap entirely: the typed-answer diff
  // replaces the back's nothing-typed image, and the HTML in front of it is the real content.
  var image by
    remember(cardId, side, forceNative) {
      mutableStateOf<SideImage>(
        if (forceNative) {
          SideImage.None
        } else {
          CardBitmaps.cached(cardId, side)?.let { SideImage.Ready(it) } ?: SideImage.Loading
        }
      )
    }
  if (image == SideImage.Loading) {
    LaunchedEffect(cardId, side) {
      val bitmap = withContext(Dispatchers.IO) { CardBitmaps.load(context, cardId, side, widthPx) }
      image = bitmap?.let { SideImage.Ready(it) } ?: SideImage.None
    }
  }
  CardSide(image, fallbackHtml, spokenText, fontScale, cardFont, modifier)
}

/** Width to decode card images at; the window size can still read 0 before the first layout. */
@Composable
private fun decodeWidthPx(): Int =
  LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
    ?: LocalContext.current.resources.displayMetrics.widthPixels

/** Decode the next cards' images in the background so moving on doesn't wait on a decode. */
@Composable
internal fun PrefetchUpcomingImages(session: ReviewSession, cardId: Long, prefetchTrigger: Int) {
  val context = LocalContext.current
  val widthPx = decodeWidthPx()
  LaunchedEffect(session, cardId, prefetchTrigger) {
    withContext(Dispatchers.IO) {
      CardBitmaps.prefetch(context, listOf(cardId) + session.upcomingCardIds(2u), widthPx)
    }
  }
}
