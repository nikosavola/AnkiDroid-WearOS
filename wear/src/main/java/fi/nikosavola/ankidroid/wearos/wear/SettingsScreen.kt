package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.Text
import uniffi.wearos_core.*

@Composable
internal fun MainActivity.SettingsScreen() {
  val activity = this
  val listState = rememberTransformingLazyColumnState()
  val transformSpec = rememberEdgeMorphSpec()
  ScreenScaffold(
    scrollState = listState,
    edgeButton = {
      EdgeButton(onClick = { popBackStackAndRunSideEffects() }, buttonSize = EdgeButtonSize.Medium) {
        Icon(painter = painterResource(id = R.drawable.ic_check), contentDescription = "Done")
      }
    },
  ) { contentPadding ->
    TransformingLazyColumn(
      state = listState,
      contentPadding = contentPadding,
      modifier = Modifier.fillMaxSize().rotaryScroll(listState),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Text(
          text = "Settings",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(vertical = 6.dp).transformedHeight(this, transformSpec),
        )
      }
      item {
        settingChoiceRow(
          label = "Text size",
          options = listOf(0.9f to "S", 1.0f to "M", 1.3f to "L", 1.6f to "XL", 1.8f to "2XL"),
          selected = fontScale,
          modifier = Modifier.transformedHeight(this, transformSpec),
        ) { scale ->
          fontScale = scale
          WatchSettings.setFontScale(activity, scale)
        }
      }
      item {
        // Live preview at the chosen size + font.
        val previewTypeface =
          when (cardFont) {
            WatchSettings.CardFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
            WatchSettings.CardFont.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace
            WatchSettings.CardFont.DEFAULT -> androidx.compose.ui.text.font.FontFamily.Default
          }
        Text(
          text = "あア 漢字 Aa 12",
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontSize = (16 * fontScale).sp,
              fontFamily = previewTypeface,
            ),
          modifier = Modifier.transformedHeight(this, transformSpec),
        )
      }
      item {
        settingChoiceRow(
          label = "Card font",
          options =
            listOf(
              WatchSettings.CardFont.DEFAULT to "Sans",
              WatchSettings.CardFont.SERIF to "Serif",
              WatchSettings.CardFont.MONOSPACE to "Mono",
            ),
          selected = cardFont,
          modifier = Modifier.transformedHeight(this, transformSpec),
        ) { font ->
          cardFont = font
          WatchSettings.setCardFont(activity, font)
        }
      }
      settingSwitchItem(
        checked = ttsEnabled,
        label = "Voice reviews",
        secondaryLabel = "Read cards aloud (TTS)",
        transformSpec = transformSpec,
      ) {
        ttsEnabled = it
        WatchSettings.setTtsEnabled(activity, it)
      }
      item {
        settingChoiceRow(
          label = "Speech rate",
          options = listOf(0.7f to "Slow", 1.0f to "Normal", 1.4f to "Fast"),
          selected = speechRate,
          modifier = Modifier.transformedHeight(this, transformSpec),
        ) { rate ->
          speechRate = rate
          WatchSettings.setSpeechRate(activity, rate)
          tts?.setSpeechRate(rate)
        }
      }
      settingSwitchItem(
        checked = keepScreenOn,
        label = "Keep screen on",
        secondaryLabel = "During review · uses battery",
        transformSpec = transformSpec,
      ) {
        keepScreenOn = it
        WatchSettings.setKeepScreenOn(activity, it)
      }
      appearanceToggles(transformSpec)
    }
  }
}
