package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.angularSize
import androidx.wear.compose.foundation.background
import androidx.wear.compose.foundation.curvedColumn
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.curvedText
import uniffi.wearos_core.WearosCard

// Angular gap (degrees) between adjacent segments. Round caps would stick out half the two-line
// pill's thickness past each end, more than any gap that still fits four pills on the bottom arc,
// so the segments are square-ended and the gap alone separates them.
private const val GAP_SWEEP_DEG = 3f
// The tap strip covers the bottom this fraction of the screen (where the arc sits), so it doesn't
// swallow taps meant for the card text / reveal above it.
private const val TAP_STRIP_FRACTION = 0.42f
// Tint of the pill fill over the background. Kept low so the full-strength grade color on top
// clears 4.5:1 even for Again's red at 11sp (it was 4.07:1 at 0.32).
internal const val PILL_FILL_ALPHA = 0.2f

/**
 * Grade buttons as curved segments hugging the bottom bezel: an arc background
 * ([CurvedModifier.background]) with centered [curvedText], one per grade.
 *
 * wear-compose has no clickable curved modifier, so taps are handled by a single pointerInput strip
 * over the bottom arc that maps the tap's x-position to a button (the pills are ordered left->right
 * along the arc, matching [gradeSpecs]). This reliably receives taps and leaves the card area above
 * untouched. Each pill shows the grade name with what that grade would schedule beneath it (from
 * [WearosCard.nextReviewTimes]), so the intervals are visible without scrolling.
 */

@Composable
fun GradeEdgeButtons(
  card: WearosCard,
  enabled: Boolean,
  colored: Boolean,
  chrome: @Composable () -> Unit = {},
  onGrade: (ease: Int) -> Unit,
) {
  // Stable across recompositions of the same card so the pointerInput gesture isn't re-registered
  // each frame (gradeSpecs depends only on the button count).
  val grades = remember(card.buttonCount) { gradeSpecs(card) }
  // One label per button, in gradeSpecs order. A phone that sent no scheduling config yields an
  // empty list, and a stale/mismatched one would mislabel buttons, so both fall back to names only.
  val labels = card.nextReviewTimes.takeIf { it.size == grades.size }.orEmpty()
  // Neutral color for the uncolored mode; read here (a @Composable context), not inside CurvedScope.
  val neutral = MaterialTheme.colorScheme.onSurfaceVariant
  // Segments are opaque (the tint blended over the background) so the answer scrolling underneath
  // never shows through the labels.
  val background = MaterialTheme.colorScheme.background
  // Taps on the bottom strip map x -> button (grades.size is 3 or 4, Anki's ease scheme). The
  // handler sits on this root Box because Compose only shares a gesture with the node's direct
  // siblings, here the scrolling answer. It never consumes the down, so everything else on the
  // screen sees the gesture as before; a drag that scrolls consumes the moves and cancels the tap.
  val tapStrip =
    if (!enabled) {
      Modifier
    } else {
      Modifier.pointerInputSharedWithSiblings(grades) {
        awaitEachGesture {
          val down = awaitFirstDown()
          if (down.position.y < size.height * (1f - TAP_STRIP_FRACTION)) return@awaitEachGesture
          val up = waitForUpOrCancellation() ?: return@awaitEachGesture
          up.consume()
          val zone = (up.position.x / size.width * grades.size).toInt().coerceIn(0, grades.size - 1)
          onGrade(grades[zone].ease)
        }
      }
    }
  Box(modifier = Modifier.fillMaxSize().then(tapStrip)) {
    // Visual layer: the caller's [chrome] (progress ring etc.) and the pills, cached as one GPU layer
    // so the arcs and rotated glyphs aren't re-rasterized while the card scrolls underneath. Pills
    // are sized to their text (so the label is centered inside each pill) and decorative for a11y:
    // curvedText would otherwise expose each label as its own node, duplicating the accessible
    // overlay below, which is the single labelled + activatable representation.
    Box(modifier = Modifier.fillMaxSize().cachedLayer()) {
      // Fades a long answer out before it reaches the pills, instead of letting lines show through
      // the gaps between them. Scrolling still brings every line up into the clear.
      Box(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(TAP_STRIP_FRACTION)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to background))
      )
      chrome()
      CurvedLayout(
      modifier = Modifier.clearAndSetSemantics {},
        anchor = 90f,
        anchorType = AnchorType.Center,
        angularDirection = CurvedDirection.Angular.Reversed,
      ) {
        grades.forEachIndexed { i, g ->
          if (i > 0) curvedRow(modifier = CurvedModifier.angularSize(GAP_SWEEP_DEG)) {}
          val pill = if (colored) g.color else neutral
          // Name and interval stack radially inside one pill, so showing the interval makes the pill
          // thicker rather than longer and four of them still fit the bottom arc.
          curvedColumn(
            angularAlignment = CurvedAlignment.Angular.Center,
            modifier =
              CurvedModifier.background(
                color = pill.copy(alpha = PILL_FILL_ALPHA).compositeOver(background),
                cap = StrokeCap.Butt,
              )
                .padding(angular = 10.dp, radial = 4.dp),
          ) {
            curvedText(text = g.text, color = pill, fontSize = 13.sp)
            labels.getOrNull(i)?.let { label ->
              curvedText(text = label, color = pill, fontSize = 11.sp)
            }
          }
        }
      }
    }
    if (enabled) {
      // Accessibility: the pointerInput strip is invisible to TalkBack, so overlay one focusable,
      // activatable element per grade zone (same left->right order + equal widths as the tap
      // mapping). They carry no pointer input, so sighted taps still reach the handler on the root.
      Row(
        modifier =
          Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(TAP_STRIP_FRACTION)
      ) {
        grades.forEachIndexed { i, g ->
          val label = labels.getOrNull(i)
          Box(
            modifier =
              Modifier.weight(1f).fillMaxHeight().semantics {
                contentDescription = if (label != null) "${g.text}, $label" else g.text
                onClick(label = "Grade ${g.text}") {
                  onGrade(g.ease)
                  true
                }
              }
          )
        }
      }
    }
  }
}

/**
 * Like `pointerInput(key)`, but the gesture also reaches the siblings underneath. The grade strip
 * sits over the bottom of the scrolling answer, and a plain pointerInput would swallow every swipe
 * that starts there; shared, a drag scrolls the answer (whose consumption cancels the tap) while a
 * tap still grades.
 */
private fun Modifier.pointerInputSharedWithSiblings(key: Any?, handler: PointerInputEventHandler) =
  this then SharedPointerInputElement(key, handler)

private class SharedPointerInputElement(val key: Any?, val handler: PointerInputEventHandler) :
  ModifierNodeElement<SharedPointerInputNode>() {
  override fun create() = SharedPointerInputNode(handler)

  override fun update(node: SharedPointerInputNode) = node.update(handler)

  // Keyed like pointerInput: an unchanged key keeps the running gesture detector.
  override fun equals(other: Any?) = other is SharedPointerInputElement && other.key == key

  override fun hashCode() = key.hashCode()
}

private class SharedPointerInputNode(handler: PointerInputEventHandler) :
  DelegatingNode(), PointerInputModifierNode {
  private val input = delegate(SuspendingPointerInputModifierNode(handler))

  fun update(handler: PointerInputEventHandler) {
    input.pointerInputEventHandler = handler
  }

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
    input.onPointerEvent(pointerEvent, pass, bounds)

  override fun onCancelPointerInput() = input.onCancelPointerInput()

  override fun sharePointerInputWithSiblings() = true
}
