package com.ichi2.anki.wearos.wear

import androidx.compose.ui.graphics.Color
import uniffi.wearos_core.WearosCard

/** A grade button: its label, chip color, and the Anki ease it answers with. */
data class GradeSpec(val text: String, val color: Color, val ease: Int)

/**
 * The grade buttons for a card, matching Anki's ease scheme: 3-button cards (learning/new) drop
 * "Hard", so their eases are Again/Good/Easy = 1/3/4; 4-button cards use Again/Hard/Good/Easy =
 * 1/2/3/4. Ease 2 is therefore reachable only on 4-button cards. Pure so the mapping is unit-tested.
 */
fun gradeSpecs(card: WearosCard): List<GradeSpec> =
  if (card.buttonCount == 3) {
    listOf(
      GradeSpec("Again", Color(0xFFEF5350), 1),
      GradeSpec("Good", Color(0xFF66BB6A), 3),
      GradeSpec("Easy", Color(0xFF42A5F5), 4),
    )
  } else {
    listOf(
      GradeSpec("Again", Color(0xFFEF5350), 1),
      GradeSpec("Hard", Color(0xFFFFA726), 2),
      GradeSpec("Good", Color(0xFF66BB6A), 3),
      GradeSpec("Easy", Color(0xFF42A5F5), 4),
    )
  }
