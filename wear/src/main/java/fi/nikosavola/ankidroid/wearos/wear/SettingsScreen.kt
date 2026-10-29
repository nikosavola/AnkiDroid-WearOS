package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.TextToggleButton
import androidx.wear.compose.material3.TextToggleButtonDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
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
      appearanceToggles(activity, transformSpec)
      aboutSection(activity, transformSpec)
    }
  }
}

/**
 * Trademark and licence notice. Both names describe what the app talks to, so this states plainly
 * that the app is not theirs, and names the source the AGPL asks us to offer with the APK.
 */
internal fun TransformingLazyColumnScope.aboutSection(
  activity: MainActivity,
  transformSpec: TransformationSpec,
) {
  item {
    Text(
      text = "About",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(vertical = 6.dp).transformedHeight(this, transformSpec),
    )
  }
  item {
    Text(
      text = "Wear for AnkiDroid ${activity.appVersionName()}",
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.transformedHeight(this, transformSpec),
    )
  }
  item {
    Text(
      text =
        "Anki® is a registered trademark of Ankitects Pty Ltd, AnkiDroid a trademark of the " +
          "AnkiDroid Open Source Team. Unofficial third-party companion, not affiliated with " +
          "or endorsed by either.",
      style = MaterialTheme.typography.labelSmall,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 8.dp).transformedHeight(this, transformSpec),
    )
  }
  item {
    Text(
      text =
        "AGPL-3.0-or-later. Bundles Anki's rslib scheduler code (AGPL-3.0, Copyright Ankitects " +
          "Pty Ltd and contributors). Source and licence texts: " +
          "github.com/nikosavola/AnkiDroid-WearOS",
      style = MaterialTheme.typography.labelSmall,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 8.dp).transformedHeight(this, transformSpec),
    )
  }
}

/** Version shown in the about block; the watch app has no BuildConfig, so ask the package manager. */
private fun MainActivity.appVersionName(): String =
  runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

/**
 * A labelled group of mutually-exclusive [TextToggleButton]s; the selected one is checked and
 * morphs. Groups of more than three options wrap onto extra rows so the labels stay legible at
 * watch width.
 */
@Composable
internal fun <T> settingChoiceRow(
  label: String,
  options: List<Pair<T, String>>,
  selected: T,
  modifier: Modifier = Modifier,
  onSelect: (T) -> Unit,
) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 2.dp),
    )
    options.chunked(3).forEach { row ->
      ButtonGroup(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        row.forEach { (value, text) ->
          val interactionSource = remember { MutableInteractionSource() }
          TextToggleButton(
            checked = value == selected,
            onCheckedChange = { onSelect(value) },
            shapes = TextToggleButtonDefaults.variantAnimatedShapes(),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f).animateWidth(interactionSource),
          ) {
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }
}

/** A full-width settings toggle row that morphs with the edge-hugging list like the other items. */
internal fun TransformingLazyColumnScope.settingSwitchItem(
  checked: Boolean,
  label: String,
  secondaryLabel: String,
  transformSpec: TransformationSpec,
  onCheckedChange: (Boolean) -> Unit,
) {
  item {
    SwitchButton(
      checked = checked,
      onCheckedChange = onCheckedChange,
      label = { Text(label) },
      secondaryLabel = { Text(secondaryLabel) },
      transformation = SurfaceTransformation(transformSpec),
      modifier = Modifier.fillMaxWidth(0.95f).transformedHeight(this, transformSpec),
    )
  }
}

/** The minimal-view appearance toggles (grade-button color + hide clock/progress in review). */
internal fun TransformingLazyColumnScope.appearanceToggles(
  activity: MainActivity,
  transformSpec: TransformationSpec,
) {
  settingSwitchItem(
    checked = activity.coloredGradeButtons,
    label = "Grade colors",
    secondaryLabel = "Color the buttons",
    transformSpec = transformSpec,
  ) {
    activity.coloredGradeButtons = it
    WatchSettings.setColoredGradeButtons(activity, it)
  }
  settingSwitchItem(
    checked = activity.hideProgress,
    label = "Hide progress",
    secondaryLabel = "The N/M count",
    transformSpec = transformSpec,
  ) {
    activity.hideProgress = it
    WatchSettings.setHideProgress(activity, it)
  }
  settingSwitchItem(
    checked = activity.hideClock,
    label = "Hide clock",
    secondaryLabel = "The time",
    transformSpec = transformSpec,
  ) {
    activity.hideClock = it
    WatchSettings.setHideClock(activity, it)
  }
}
