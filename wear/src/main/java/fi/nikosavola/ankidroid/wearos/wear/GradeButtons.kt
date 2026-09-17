package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import uniffi.wearos_core.WearosCard

// Angular gap (degrees) between adjacent pills. Sized to clear the StrokeCap.Round caps, which
// extend ~half the pill thickness past each end, so a smaller gap lets adjacent caps overlap.
private const val GAP_SWEEP_DEG = 18f
// The tap strip covers the bottom this fraction of the screen (where the arc sits), so it doesn't
// swallow taps meant for the card text / reveal above it.
private const val TAP_STRIP_FRACTION = 0.42f

/**
 * Grade buttons as real curved pills hugging the bottom bezel: a rounded-cap arc background
 * ([CurvedModifier.background] + StrokeCap.Round) with centered [curvedText], one per grade.
 *
 * wear-compose has no clickable curved modifier, so taps are handled by a single pointerInput strip
 * over the bottom arc that maps the tap's x-position to a button (the pills are ordered left->right
 * along the arc, matching [gradeSpecs]). This reliably receives taps and leaves the card area above
 * untouched. The pills carry the grade name only (they have no room for more text); what each grade
 * would schedule is shown by [GradeIntervalLabels] just above them.
 */

@Composable
fun GradeEdgeButtons(
  card: WearosCard,
  enabled: Boolean,
  colored: Boolean,
  onGrade: (ease: Int) -> Unit,
) {
  // Stable across recompositions of the same card so the pointerInput gesture isn't re-registered
  // each frame (gradeSpecs depends only on the button count).
  val grades = remember(card.buttonCount) { gradeSpecs(card) }
  // Neutral color for the uncolored mode; read here (a @Composable context), not inside CurvedScope.
  val neutral = MaterialTheme.colorScheme.onSurfaceVariant
  Box(modifier = Modifier.fillMaxSize()) {
    // Visual layer: curved pills sized to their text (so the label is centered inside each pill).
    // Decorative for a11y - curvedText would otherwise expose each label as its own node, duplicating
    // the accessible overlay below; the overlay is the single labelled + activatable representation.
    CurvedLayout(
      modifier = Modifier.clearAndSetSemantics {},
      anchor = 90f,
      anchorType = AnchorType.Center,
      angularDirection = CurvedDirection.Angular.Reversed,
    ) {
      grades.forEachIndexed { i, g ->
        if (i > 0) curvedRow(modifier = CurvedModifier.angularSize(GAP_SWEEP_DEG)) {}
        val pill = if (colored) g.color else neutral
        curvedRow(
          radialAlignment = CurvedAlignment.Radial.Center,
          modifier =
            CurvedModifier.background(color = pill.copy(alpha = 0.32f), cap = StrokeCap.Round)
              .padding(angular = 6.dp, radial = 5.dp),
        ) {
          curvedText(text = g.text, color = pill, fontSize = 13.sp)
        }
      }
    }
    // Tap layer: bottom strip mapping x -> button. grades.size is 3 or 4 (Anki's ease scheme).
    if (enabled) {
      Box(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(TAP_STRIP_FRACTION)
            .pointerInput(grades) {
              detectTapGestures { offset ->
                val zone =
                  (offset.x / size.width * grades.size).toInt().coerceIn(0, grades.size - 1)
                onGrade(grades[zone].ease)
              }
            }
      )
      // Accessibility: the pointerInput strip is invisible to TalkBack, so overlay one focusable,
      // activatable element per grade zone (same left->right order + equal widths as the tap
      // mapping). They carry no pointer input, so sighted taps still fall through to the strip above.
      Row(
        modifier =
          Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(TAP_STRIP_FRACTION)
      ) {
        grades.forEach { g ->
          Box(
            modifier =
              Modifier.weight(1f).fillMaxHeight().semantics {
                contentDescription = g.text
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
 * What each grade button would schedule ("1m · 6m · 10m · 4d"), in the same left-to-right order as
 * the curved pills, so the numbers line up with the buttons without crowding them.
 *
 * AnkiDroid ships one label per button; the Rust core recomputes them when a card is graded and
 * requeued, so these stay correct for the card's current state. Rendered inside the scrolling card
 * content (above the pills and clear of the round bezel) rather than on the arc itself, which has no
 * room for text without squeezing the grade names out. Renders nothing when a card carries no labels
 * (mock cards, or a phone that sent no scheduling config).
 */
@Composable
fun GradeIntervalLabels(labels: List<String>, modifier: Modifier = Modifier) {
  if (labels.isEmpty()) return
  Text(
    text = labels.joinToString(" · "),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 11.sp,
    textAlign = TextAlign.Center,
    modifier = modifier.fillMaxWidth().padding(top = 10.dp),
  )
}
