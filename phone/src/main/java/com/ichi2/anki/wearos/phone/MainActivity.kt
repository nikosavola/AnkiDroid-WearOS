package com.ichi2.anki.wearos.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
  companion object {
    private const val PERMISSION_ANKIDROID = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val PERMISSION_REQUEST_CODE = 420
    private const val NOTIF_PERMISSION_REQUEST_CODE = 421

    // Default pre-push cap shown in Settings; the sync itself lives in SyncService now.
    private const val DEFAULT_PREPUSH_DECKS = 15

    // User settings, persisted across launches.
    private const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_FORCE_MOCK = "force_mock"
    private const val KEY_PREPUSH_CAP = "prepush_cap"
    private const val KEY_READ_ONLY = "read_only"
    private const val PREPUSH_MIN = 5
    private const val PREPUSH_MAX = 30
  }

  // UI state + light orchestration live in the ViewModel (survives config changes); this Activity is
  // just the framework boundary (permissions, receiver, service start, dialogs).
  private val vm: PhoneViewModel by viewModels()

  // Mirrors SyncService progress into the in-app status line + bar while the Activity is alive.
  private val syncProgressReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != SyncService.ACTION_PROGRESS) return
        vm.onSyncProgress(
          text = intent.getStringExtra(SyncService.EXTRA_TEXT).orEmpty(),
          percent = intent.getIntExtra(SyncService.EXTRA_PERCENT, 0),
          done = intent.getBooleanExtra(SyncService.EXTRA_DONE, false),
          error = intent.getBooleanExtra(SyncService.EXTRA_ERROR, false),
        )
      }
    }

  private val settings by lazy { getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

  // Which modal dialog is open, if any. Compose state so the dialogs are declared in PhoneScreen and
  // shown/dismissed by flipping this, rather than imperatively built View trees.
  private enum class PhoneDialog {
    NONE,
    SYNC_HELP,
    SETTINGS,
    CONNECTION_INFO,
  }

  private var activeDialog by mutableStateOf(PhoneDialog.NONE)

  private fun forceMock(): Boolean =
    settings.getBoolean(KEY_FORCE_MOCK, !vm.bridge.isAnkiDroidAvailable())

  private fun prePushCap(): Int = settings.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS)

  private fun readOnly(): Boolean = settings.getBoolean(KEY_READ_ONLY, false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Opt into edge-to-edge explicitly (not just implicitly via targetSdk 35) so the intent is
    // clear
    // and the system bars get proper contrast scrims.
    enableEdgeToEdge()

    vm.updateLastSyncLabel()
    setContent { AnkiPhoneTheme { PhoneScreen() } }

    androidx.core.content.ContextCompat.registerReceiver(
      this,
      syncProgressReceiver,
      IntentFilter(SyncService.ACTION_PROGRESS),
      androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    checkPermissions()

    // Schedule (or cancel) the recurring background sync per the saved preference. WorkManager
    // keeps an already-running schedule (KEEP), so this is safe to call on every launch.
    AutoSync.applyFromSettings(this)
  }

  override fun onResume() {
    super.onResume()
    // A background auto-sync may have run while we were away; refresh the relative time.
    vm.updateLastSyncLabel()
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(syncProgressReceiver)
  }

  /**
   * The whole phone screen in Compose Material 3 Expressive. UI state comes from [vm]; the
   * framework glue (permissions, sync, receiver) stays in the Activity, and the dialogs remain
   * MaterialAlertDialogs invoked from here.
   */
  @Composable
  private fun PhoneScreen() {
    val selectedCount = vm.allDecks.count { vm.deckSelection[it.first] ?: true }
    // Sum of due counts across the selected decks; null when AnkiDroid didn't expose counts at all.
    val cardsDue: Int? =
      if (vm.deckCounts.isEmpty()) {
        null
      } else {
        vm.allDecks
          .filter { vm.deckSelection[it.first] ?: true }
          .sumOf { vm.deckCounts[it.first] ?: 0 }
      }

    // Host in a Surface so LocalContentColor is onBackground everywhere. Otherwise unstyled Text
    // (the deck-tree leaf rows, the headline) falls back to the default content color and is nearly
    // invisible in dark mode.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(
        modifier = Modifier.safeDrawingPadding().imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        BrandHeader()
        StatusChip()
        DueHeroCard(cardsDue = cardsDue, selectedCount = selectedCount)

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        ) {
          Text(
            text = "Decks to sync",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = "$selectedCount / ${vm.allDecks.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = vm.lastSyncLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
          value = vm.deckFilter,
          onValueChange = { vm.deckFilter = it.trim() },
          label = { Text("Search decks") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { vm.setAllDecksSelected(true) }) { Text("Select all") }
          OutlinedButton(onClick = { vm.setAllDecksSelected(false) }) { Text("Select none") }
        }

        DeckTreeContent(modifier = Modifier.weight(1f))

        vm.syncStatusMessage?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Keep the sync status on one line; truncate rather than reflow and shove the button down.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (vm.progressVisible) {
          if (vm.progressIndeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(
              progress = { vm.progressFraction },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        Button(
          onClick = { triggerSyncToWatch() },
          enabled = vm.syncEnabled,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
          Text(
            if (cardsDue != null && cardsDue > 0) {
              "Sync $cardsDue cards to watch"
            } else {
              "Sync due cards to watch"
            }
          )
        }
      }
    }

    when (activeDialog) {
      PhoneDialog.SYNC_HELP -> SyncHelpDialog { activeDialog = PhoneDialog.NONE }
      PhoneDialog.SETTINGS -> SettingsDialog { activeDialog = PhoneDialog.NONE }
      PhoneDialog.CONNECTION_INFO -> ConnectionInfoDialog { activeDialog = PhoneDialog.NONE }
      PhoneDialog.NONE -> {}
    }
  }

  /** Branded header: dynamic-colored app mark on a primary chip, the wordmark, and overflow menu. */
  @Composable
  private fun BrandHeader() {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          // The monochrome launcher mark, tinted onPrimary, so the badge tracks the dynamic palette.
          Icon(
            painter = painterResource(id = R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(30.dp),
          )
        }
      }
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "ANKIDROID",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "WearOS Companion",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      OverflowMenu()
    }
  }

  /** Connection status as a rounded chip (colored dot + message); tapping opens the info dialog. */
  @Composable
  private fun StatusChip() {
    val dark = isSystemInDarkTheme()
    // "Connected" gets a semantic green (M3's dynamic palette has no success role); error + neutral
    // reuse theme roles so they still track the wallpaper palette.
    val bg: Color
    val fg: Color
    when (vm.statusTone) {
      StatusTone.CONNECTED -> {
        bg = if (dark) Color(0xFF14361F) else Color(0xFFDDF3E4)
        fg = if (dark) Color(0xFF7CE0A3) else Color(0xFF12683B)
      }
      StatusTone.ERROR -> {
        bg = MaterialTheme.colorScheme.errorContainer
        fg = MaterialTheme.colorScheme.onErrorContainer
      }
      StatusTone.NEUTRAL -> {
        bg = MaterialTheme.colorScheme.surfaceVariant
        fg = MaterialTheme.colorScheme.onSurfaceVariant
      }
    }
    Surface(
      shape = RoundedCornerShape(percent = 50),
      color = bg,
      modifier = Modifier.fillMaxWidth().clickable { activeDialog = PhoneDialog.CONNECTION_INFO },
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(10.dp))
        Text(
          text = vm.statusMessage,
          color = fg,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Icon(
          painter = painterResource(id = R.drawable.ic_info),
          contentDescription = "Connection details",
          tint = fg,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }

  /** The "cards due to sync" hero: the due total across selected decks on a primary-colored card. */
  @Composable
  private fun DueHeroCard(cardsDue: Int?, selectedCount: Int) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Text(
          text = "Cards due to sync",
          style = MaterialTheme.typography.titleMedium,
          color = onPrimary.copy(alpha = 0.9f),
        )
        Text(
          text = cardsDue?.toString() ?: "—",
          style = MaterialTheme.typography.displayMedium,
          fontWeight = FontWeight.Bold,
          color = onPrimary,
        )
        val decks = "$selectedCount selected deck" + if (selectedCount == 1) "" else "s"
        Text(
          text = if (cardsDue != null) "across $decks" else "$decks · counts unavailable",
          style = MaterialTheme.typography.bodyMedium,
          color = onPrimary.copy(alpha = 0.9f),
        )
      }
    }
  }

  /** Three-dot overflow: sync help, settings, a manual AnkiDroid re-query, permission re-request. */
  @Composable
  private fun OverflowMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box {
      IconButton(onClick = { expanded = true }) {
        Icon(
          painter = painterResource(id = R.drawable.ic_more_vert),
          contentDescription = "More options",
        )
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
          text = { Text("How syncing works") },
          onClick = {
            expanded = false
            activeDialog = PhoneDialog.SYNC_HELP
          },
        )
        DropdownMenuItem(
          text = { Text("Settings") },
          onClick = {
            expanded = false
            activeDialog = PhoneDialog.SETTINGS
          },
        )
        DropdownMenuItem(
          text = { Text("Force sync with AnkiDroid") },
          onClick = {
            expanded = false
            forceSyncWithAnkiDroid()
          },
        )
        DropdownMenuItem(
          text = { Text("Grant permissions") },
          onClick = {
            expanded = false
            checkPermissions()
          },
        )
      }
    }
  }

  /**
   * Re-query the deck list from AnkiDroid's ContentProvider and redraw. Note: AnkiDroid caches its
   * collection in-process, so a brand-new deck may only appear here after AnkiDroid itself reloads.
   */
  private fun forceSyncWithAnkiDroid() {
    Toast.makeText(this, "Refreshing decks from AnkiDroid…", Toast.LENGTH_SHORT).show()
    vm.loadDecks()
  }

  /**
   * Explains the sync model: what's pushed to the watch and how watch reviews return to AnkiDroid.
   * Bold section headings, hanging-indented bullets, and a little italic emphasis on two phrases.
   */
  @Composable
  private fun SyncHelpDialog(onDismiss: () -> Unit) {
    val italic = SpanStyle(fontStyle = FontStyle.Italic)

    // Bullet, with an optional phrase italicized in place (was BulletSpan + StyleSpan(ITALIC)).
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

  private fun checkPermissions() {
    if (checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED) {
      vm.loadDecks()
    } else {
      vm.setStatus("Waiting for card access permission…", StatusTone.NEUTRAL)
      requestPermissions(arrayOf(PERMISSION_ANKIDROID), PERMISSION_REQUEST_CODE)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == PERMISSION_REQUEST_CODE) {
      if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        vm.loadDecks()
      } else {
        vm.setStatus("Permission denied — can't read cards", StatusTone.ERROR)
        Toast.makeText(this, "Permission required to access cards!", Toast.LENGTH_LONG).show()
      }
    }
  }

  /** The Compose Material 3 Expressive deck tree, wired to the persisted selection + due counts. */
  @Composable
  private fun DeckTreeContent(modifier: Modifier = Modifier) {
    val filtered =
      if (vm.deckFilter.isBlank()) {
        vm.allDecks
      } else {
        vm.allDecks.filter { it.second.contains(vm.deckFilter, ignoreCase = true) }
      }
    if (filtered.isEmpty()) {
      Text(
        text =
          if (vm.deckFilter.isBlank()) {
            "No decks found."
          } else {
            "No decks match \"${vm.deckFilter}\"."
          },
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
      )
    } else {
      val root = remember(filtered) { buildDeckTree(filtered) }
      DeckSelectionTree(
        root = root,
        checkedOf = { vm.deckSelection[it] ?: true },
        onDeckChecked = { id, checked -> vm.setDeckSelected(id, checked) },
        onFolderChecked = { node, checked -> node.deckIds().forEach { vm.setDeckSelected(it, checked) } },
        dueCountOf = { node ->
          node.deckIds().mapNotNull { vm.deckCounts[it] }.takeIf { it.isNotEmpty() }?.sum()
        },
        modifier = modifier,
      )
    }
  }

  /**
   * Kick off the sync in [SyncService] (a foreground service) so it keeps running with a progress
   * notification even if the app is backgrounded. Progress is mirrored back into the in-app bar via
   * [syncProgressReceiver] while the Activity is alive.
   */
  private fun triggerSyncToWatch() {
    // Notification permission is runtime-gated on API 33+; ask so the background progress shows.
    if (
      android.os.Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(
        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
        NOTIF_PERMISSION_REQUEST_CODE,
      )
    }

    vm.onSyncStarting()

    androidx.core.content.ContextCompat.startForegroundService(
      this,
      Intent(this, SyncService::class.java),
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

  /** Settings dialog opened from the overflow menu: mock/read-only/auto-sync + interval and cap. */
  @Composable
  private fun SettingsDialog(onDismiss: () -> Unit) {
    var mock by remember { mutableStateOf(forceMock()) }
    var ro by remember { mutableStateOf(readOnly()) }
    var auto by remember { mutableStateOf(AutoSync.isEnabled(this@MainActivity)) }
    var interval by remember { mutableIntStateOf(AutoSync.intervalHours(this@MainActivity)) }
    var cap by remember { mutableIntStateOf(prePushCap().coerceIn(PREPUSH_MIN, PREPUSH_MAX)) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Settings") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          SettingSwitchRow("Force Mock Mode (Demo / Testing)", mock) { mock = it }
          SettingSwitchRow("Read-only mode (don't write reviews back)", ro) { ro = it }
          SettingSwitchRow("Auto-sync decks in background", auto) { auto = it }
          Text(
            text = "Auto-sync every: ${interval}h",
            style = MaterialTheme.typography.bodyMedium,
            color = if (auto) MaterialTheme.colorScheme.onSurface else dim,
            modifier = Modifier.padding(top = 4.dp),
          )
          Slider(
            value = interval.toFloat(),
            onValueChange = { interval = it.roundToInt() },
            valueRange = AutoSync.MIN_INTERVAL_HOURS.toFloat()..AutoSync.MAX_INTERVAL_HOURS.toFloat(),
            steps = (AutoSync.MAX_INTERVAL_HOURS - AutoSync.MIN_INTERVAL_HOURS - 1).coerceAtLeast(0),
            enabled = auto,
          )
          Text(
            text = "Decks to pre-push on sync: $cap",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
          )
          Slider(
            value = cap.toFloat(),
            onValueChange = { cap = (it / 5).roundToInt() * 5 },
            valueRange = PREPUSH_MIN.toFloat()..PREPUSH_MAX.toFloat(),
            steps = ((PREPUSH_MAX - PREPUSH_MIN) / 5 - 1).coerceAtLeast(0),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            settings
              .edit()
              .putBoolean(KEY_FORCE_MOCK, mock)
              .putBoolean(KEY_READ_ONLY, ro)
              .putInt(KEY_PREPUSH_CAP, cap)
              .apply()
            // Persists the flag + interval and (re)schedules the WorkManager job.
            AutoSync.apply(this@MainActivity, auto, interval)
            onDismiss()
          }
        ) {
          Text("Done")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
  }

  /**
   * Debug info about the app, the AnkiDroid connection, the current settings, and the watch link,
   * shown when the status chip is tapped. Grouped into sections and copyable for bug reports. The
   * selected-deck ContentProvider query and the connected-watch list resolve asynchronously and fill
   * in once ready; everything else is available immediately.
   */
  @Composable
  private fun ConnectionInfoDialog(onDismiss: () -> Unit) {
    val available = remember { vm.bridge.isAnkiDroidAvailable() }
    val permGranted = checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED
    val notifGranted =
      android.os.Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
    val deckPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    // Key on the raw long (.value); "deck_$deckId" on the DeckId value class yields
    // "deck_DeckId(value=123)", which never matches the write side, so every deck reads as selected.
    val selectedCount = vm.allDecks.count { deckPrefs.getBoolean("deck_${it.first.value}", true) }

    var selectedDeck by remember { mutableStateOf("checking…") }
    var watches by remember { mutableStateOf("checking…") }

    LaunchedEffect(Unit) {
      selectedDeck =
        withContext(Dispatchers.IO) {
          try {
            if (available) {
              vm.bridge.getSelectedDeck().let { "${it.second} (${it.first})" }
            } else {
              "n/a"
            }
          } catch (e: Exception) {
            "error"
          }
        }
      watches =
        try {
          com.google.android.gms.wearable.Wearable.getNodeClient(this@MainActivity)
            .connectedNodes
            .await()
            .joinToString(", ") { node -> node.displayName + if (node.isNearby) " (nearby)" else "" }
            .ifEmpty { "none" }
        } catch (e: Exception) {
          "error: ${e.message}"
        }
    }

    val report =
      remember(selectedDeck, watches, selectedCount) {
        listOf(
            "▸ App",
            "   Version: ${appVersionString(packageName)}",
            "   Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "   Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "",
            "▸ AnkiDroid",
            "   Provider: ${if (available) "connected" else "not found"}",
            "   App: ${appVersionString("com.ichi2.anki")}",
            "   Authority: com.ichi2.anki.flashcards",
            "   Read/write permission: ${if (permGranted) "granted" else "denied"}",
            "   Selected deck: $selectedDeck",
            "   Decks visible: ${vm.allDecks.size}",
            "   Decks selected to sync: $selectedCount / ${vm.allDecks.size}",
            "",
            "▸ Settings",
            "   Force mock mode: ${if (forceMock()) "on" else "off"}",
            "   Read-only mode: ${if (readOnly()) "on" else "off"}",
            "   Auto-sync (background): ${
              if (AutoSync.isEnabled(this@MainActivity)) {
                "every ${AutoSync.intervalHours(this@MainActivity)}h"
              } else {
                "off"
              }
            }",
            "   Pre-push cap: ${prePushCap()} decks",
            "   Notifications: ${if (notifGranted) "granted" else "denied"}",
            "",
            "▸ Watch link",
            "   Connected watches: $watches",
          )
          .joinToString("\n")
      }

    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Connection info") },
      text = {
        Text(
          text = report,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.verticalScroll(rememberScrollState()),
        )
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
      // Copy reads the current report, so it includes whatever the async fill-in has resolved.
      dismissButton = { TextButton(onClick = { copyDebugInfo(report) }) { Text("Copy") } },
    )
  }

  /** "versionName (versionCode)" for [pkg], or "not installed" when it isn't present. */
  private fun appVersionString(pkg: String): String =
    try {
      val info = packageManager.getPackageInfo(pkg, 0)
      // PackageInfoCompat picks longVersionCode / versionCode by API level, so no deprecated call.
      val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
      "${info.versionName} ($code)"
    } catch (e: Exception) {
      if (pkg == packageName) "?" else "not installed"
    }

  /** Copy the debug report to the clipboard so it can be pasted into a bug report. */
  private fun copyDebugInfo(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(
      android.content.ClipData.newPlainText("AnkiDroid Wear debug info", text)
    )
    Toast.makeText(this, "Copied debug info", Toast.LENGTH_SHORT).show()
  }
}

/** A node in the deck hierarchy parsed from Anki `::`-separated deck names. */
class DeckNode(val name: String) {
  var deckId: DeckId? = null
  val children = sortedMapOf<String, DeckNode>()
}

/** Build a folder tree from flat `parent::child` deck names. */
internal fun buildDeckTree(decks: List<Pair<DeckId, String>>): DeckNode {
  val root = DeckNode("")
  for ((id, fullName) in decks) {
    var node = root
    for (segment in fullName.split("::")) {
      node = node.children.getOrPut(segment) { DeckNode(segment) }
    }
    node.deckId = id
  }
  return root
}

/** Collect every real deck id at or below [node]. */
internal fun collectDeckIds(node: DeckNode, out: MutableList<DeckId>) {
  node.deckId?.let { out.add(it) }
  for (child in node.children.values) collectDeckIds(child, out)
}

/** Every real deck id at or below this node — an idiomatic `buildList` wrapper. */
internal fun DeckNode.deckIds(): List<DeckId> = buildList { collectDeckIds(this@deckIds, this) }
