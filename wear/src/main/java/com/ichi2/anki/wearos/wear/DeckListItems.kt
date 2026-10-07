package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text

// Presentational rows for the deck picker, and the glanceable stat used on the deck/session screens.
// Kept top-level (state + click callbacks passed in, no Activity coupling) so they're reusable and
// render-testable outside MainActivity. The navigation each row triggers stays in the Activity via
// the onClick lambdas.

/** A folder row (Anki parent deck): folder icon, name, and a "Folder" subtitle. Taps drill in. */
@Composable
fun FolderCard(
  name: String,
  modifier: Modifier,
  onClick: () -> Unit,
  transformation: SurfaceTransformation? = null,
) {
  Card(onClick = onClick, transformation = transformation, modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        painter = painterResource(id = R.drawable.ic_folder),
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.width(10.dp))
      Column {
        Text(
          text = name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "Folder",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * A studyable deck row. [dueNow] null means the deck isn't pooled on the watch yet, shown as "Tap to
 * download"; otherwise "N cards ready" in green. Taps open it offline if pooled, else request it.
 */
@Composable
fun DeckCard(
  label: String,
  dueNow: Int?,
  modifier: Modifier,
  onClick: () -> Unit,
  transformation: SurfaceTransformation? = null,
) {
  Card(onClick = onClick, transformation = transformation, modifier = modifier) {
    Column {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text =
          when (dueNow) {
            null -> "Tap to download"
            1 -> "1 card ready"
            else -> "$dueNow cards ready"
          },
        style = MaterialTheme.typography.labelSmall,
        color =
          if (dueNow != null) {
            MaterialTheme.colorScheme.tertiary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}

/**
 * A glanceable stat: a small caps label above a bold M3 Numeral (tabular digits). Used for the
 * deck/session counts where the number is the point.
 */
@Composable
fun StatNumeral(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
    Text(
      text = label.uppercase(),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.numeralMedium, color = color)
  }
}
