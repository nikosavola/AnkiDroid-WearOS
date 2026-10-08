package com.ichi2.anki.wearos.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Shows whichever dialog [PhoneViewModel.activeDialog] names; that state survives rotation. */
@Composable
fun PhoneDialogs(vm: PhoneViewModel, actions: PhoneActions) {
  when (vm.activeDialog) {
    PhoneDialog.SYNC_HELP -> SyncHelpDialog(vm::closeDialog)
    PhoneDialog.SETTINGS -> SettingsDialog(vm)
    PhoneDialog.CONNECTION_INFO -> ConnectionInfoDialog(vm, actions)
    PhoneDialog.NONE -> {}
  }
}

/**
 * Explains the sync model: what's pushed to the watch and how watch reviews return to AnkiDroid.
 * Bold section headings, hanging-indented bullets, and a little italic emphasis on two phrases.
 */
@Composable
private fun SyncHelpDialog(onDismiss: () -> Unit) {
  val italic = SpanStyle(fontStyle = FontStyle.Italic)

  // Bullet, with an optional phrase italicized in place.
  fun bullet(text: String, emphasize: String? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    if (emphasize != null) {
      val i = text.indexOf(emphasize)
      if (i >= 0) addStyle(italic, i, i + emphasize.length)
    }
  }
  val sections =
    listOf(
      "Phone → Watch" to
        listOf(
          bullet(
            "The phone reads your due cards from AnkiDroid and pushes the selected decks to the " +
              "watch (compressed, over Bluetooth). Only the decks you check are synced."
          ),
          bullet(
            "This happens when you tap \"Sync Due Cards to Watch\", automatically in the " +
              "background (if enabled), and when the watch requests a deck."
          ),
        ),
      "Reviewing on the watch" to
        listOf(
          bullet(
            "You review offline on the watch. Each grade is buffered locally, nothing is sent " +
              "per-card."
          ),
          bullet(
            "Scheduling mirrors Anki within the session: a card graded onto a learning step (a " +
              "sub-day interval, e.g. Again always and Hard/Good while it is still in learning) " +
              "goes back into the queue so you relearn it before the deck is done. A day-or-more " +
              "interval graduates the card out. Progress counts graduated cards, so you can't " +
              "finish without clearing the lapses.",
            emphasize = "so you can't finish without clearing the lapses",
          ),
          bullet(
            "The real schedule is still computed by AnkiDroid from the grades you send; the " +
              "in-session repeats are a local approximation for offline use.",
            emphasize = "a local approximation for offline use",
          ),
        ),
      "Watch → Phone → AnkiDroid" to
        listOf(
          bullet(
            "Buffered grades are sent back to the phone when a session finishes, when you tap " +
              "\"Sync N reviews\", or on the next background sync."
          ),
          bullet(
            "The phone writes them into AnkiDroid's scheduler (the same as answering in " +
              "AnkiDroid), then tells the watch to clear its buffer."
          ),
        ),
      "Read-only mode" to
        listOf(
          bullet(
            "When enabled (Settings), the phone accepts the watch's reviews and clears the " +
              "buffer but does not change AnkiDroid, useful for testing without affecting your " +
              "scheduling."
          )
        ),
    )

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    title = { Text("How syncing works") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        sections.forEach { (heading, bullets) ->
          Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
          )
          bullets.forEach { b ->
            Row {
              Text("•  ", style = MaterialTheme.typography.bodyMedium)
              Text(text = b, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    },
  )
}

/** A label + trailing Material switch, filling the dialog row width. */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
  ) {
    Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

/**
 * Settings dialog opened from the overflow menu: mock/read-only/auto-sync + interval and cap. Edits
 * live in [PhoneViewModel.settingsDraft] until Done, so rotating mid-edit keeps them.
 */
@Composable
private fun SettingsDialog(vm: PhoneViewModel) {
  LaunchedEffect(Unit) { vm.ensureSettingsDraft() }
  val draft = vm.settingsDraft ?: return

  fun edit(change: PhoneSettings.() -> PhoneSettings) {
    vm.settingsDraft = vm.settingsDraft?.change()
  }
  val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

  AlertDialog(
    onDismissRequest = vm::closeDialog,
    title = { Text("Settings") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        SettingSwitchRow("Force Mock Mode (Demo / Testing)", draft.forceMock) {
          edit { copy(forceMock = it) }
        }
        SettingSwitchRow("Read-only mode (don't write reviews back)", draft.readOnly) {
          edit { copy(readOnly = it) }
        }
        SettingSwitchRow("Auto-sync decks in background", draft.autoSync) {
          edit { copy(autoSync = it) }
        }
        Text(
          text = "Auto-sync every: ${draft.intervalHours}h",
          style = MaterialTheme.typography.bodyMedium,
          color = if (draft.autoSync) MaterialTheme.colorScheme.onSurface else dim,
          modifier = Modifier.padding(top = 4.dp),
        )
        val intervalState =
          rememberSliderState(
            value = draft.intervalHours.toFloat(),
            steps = (AutoSync.MAX_INTERVAL_HOURS - AutoSync.MIN_INTERVAL_HOURS - 1).coerceAtLeast(0),
            trackRange = AutoSync.MIN_INTERVAL_HOURS.toFloat()..AutoSync.MAX_INTERVAL_HOURS.toFloat(),
          )
        Slider(
          state = intervalState,
          onValueChange = {
            intervalState.value = it
            edit { copy(intervalHours = it.roundToInt()) }
          },
          enabled = draft.autoSync,
        )
        Text(
          text = "Decks to pre-push on sync: ${draft.prePushCap}",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 8.dp),
        )
        val capState =
          rememberSliderState(
            value = draft.prePushCap.toFloat(),
            steps = ((PhoneSettings.PREPUSH_MAX - PhoneSettings.PREPUSH_MIN) / 5 - 1).coerceAtLeast(0),
            trackRange = PhoneSettings.PREPUSH_MIN.toFloat()..PhoneSettings.PREPUSH_MAX.toFloat(),
          )
        Slider(
          state = capState,
          onValueChange = {
            capState.value = it
            edit { copy(prePushCap = (it / 5).roundToInt() * 5) }
          },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          vm.saveSettings(draft)
          vm.closeDialog()
        }
      ) {
        Text("Done")
      }
    },
    dismissButton = { TextButton(onClick = vm::closeDialog) { Text("Cancel") } },
  )
}

/**
 * Debug info about the app, the AnkiDroid connection, the current settings, and the watch link,
 * shown when the status chip is tapped. Built off the main thread by the ViewModel, and copyable
 * for bug reports.
 */
@Composable
private fun ConnectionInfoDialog(vm: PhoneViewModel, actions: PhoneActions) {
  var report by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { report = vm.connectionReport() }

  AlertDialog(
    onDismissRequest = vm::closeDialog,
    title = { Text("Connection info") },
    text = {
      Text(
        text = report ?: "Checking…",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
    confirmButton = { TextButton(onClick = vm::closeDialog) { Text("Close") } },
    dismissButton = {
      TextButton(onClick = { report?.let(actions.onCopyReport) }, enabled = report != null) {
        Text("Copy")
      }
    },
  )
}
