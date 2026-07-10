package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import uniffi.wearos_core.WearosCard

/**
 * Grade buttons laid out along the bottom round edge with [CurvedLayout], so each chip is rotated
 * tangent to the bezel and follows the arc. The interval preview is dropped so the words fit at this
 * size (colour signals the grade); curvedComposable children stay clickable. Grades come from the
 * pure [gradeSpecs].
 */
@Composable
fun GradeEdgeButtons(card: WearosCard, enabled: Boolean, onGrade: (ease: Int) -> Unit) {
  val grades = gradeSpecs(card)
  CurvedLayout(
    modifier = Modifier.fillMaxSize(),
    anchor = 90f,
    anchorType = AnchorType.Center,
    angularDirection = CurvedDirection.Angular.Reversed,
  ) {
    grades.forEachIndexed { i, g ->
      if (i > 0) {
        curvedComposable { Spacer(modifier = Modifier.size(2.dp)) }
      }
      curvedComposable {
        CompactButton(
          onClick = { if (enabled) onGrade(g.ease) },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = g.color.copy(alpha = 0.32f),
              contentColor = g.color,
            ),
        ) {
          Text(g.text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
  }
}
