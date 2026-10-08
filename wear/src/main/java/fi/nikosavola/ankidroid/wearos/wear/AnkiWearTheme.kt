package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Wear Material 3 theme: the watch face's dynamic colors where available (Wear OS 6+), the library
 * default elsewhere, always on black. Wear apps must use a black background (WO-V13), and the
 * phone-rendered card images are light text on transparent, so there is deliberately no light
 * theme. Motion is always the expressive scheme.
 */
@Composable
fun AnkiWearTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val scheme =
    remember(context) {
      (dynamicColorScheme(context) ?: ColorScheme()).copy(background = Color.Black)
    }
  MaterialTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
