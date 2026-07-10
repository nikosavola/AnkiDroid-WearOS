package com.ichi2.anki.wearos.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val statusColor =
      when (vm.statusTone) {
        StatusTone.CONNECTED -> MaterialTheme.colorScheme.primary
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
      }
    val selectedCount = vm.allDecks.count { vm.deckSelection[it.first] ?: true }

    // Host in a Surface so LocalContentColor is onBackground everywhere. Otherwise unstyled Text
    // (the deck-tree leaf rows, the headline) falls back to the default content color and is nearly
    // invisible in dark mode.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(
        modifier = Modifier.safeDrawingPadding().imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          verticalAlignment = Alignment.Top,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "ANKIDROID",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "WearOS Companion",
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        OverflowMenu()
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth().clickable { showConnectionInfoDialog() }.padding(vertical = 4.dp),
      ) {
        Text(text = vm.statusMessage, color = statusColor, modifier = Modifier.weight(1f))
        Icon(
          painter = painterResource(id = R.drawable.ic_info),
          contentDescription = "Connection details",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = "Decks to sync ($selectedCount/${vm.allDecks.size})",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
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
          LinearProgressIndicator(progress = { vm.progressFraction }, modifier = Modifier.fillMaxWidth())
        }
      }

      Button(
        onClick = { triggerSyncToWatch() },
        enabled = vm.syncEnabled,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      ) {
        Text("Sync Due Cards to Watch")
      }
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
            showSyncHelpDialog()
          },
        )
        DropdownMenuItem(
          text = { Text("Settings") },
          onClick = {
            expanded = false
            showSettingsDialog()
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
   */
  private fun showSyncHelpDialog() {
    com.google.android.material.dialog
      .MaterialAlertDialogBuilder(this)
      .setTitle("How syncing works")
      .setMessage(buildSyncHelpText())
      .setPositiveButton("Got it", null)
      .show()
  }

  /**
   * Rich body for the sync-help dialog: bold section headings, a little italic emphasis, and
   * hanging-indented bullets (BulletSpan is a leading-margin span, so wrapped lines line up under
   * the text rather than back under the bullet).
   */
  private fun buildSyncHelpText(): CharSequence {
    val gap = (12 * resources.displayMetrics.density).toInt()
    val sb = SpannableStringBuilder()

    fun heading(text: String) {
      if (sb.isNotEmpty()) sb.append("\n\n")
      val start = sb.length
      sb.append(text).append('\n')
      sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun bullet(text: String) {
      val start = sb.length
      sb.append(text).append('\n')
      sb.setSpan(BulletSpan(gap), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun emphasize(term: String) {
      val i = sb.indexOf(term)
      if (i >= 0) {
        sb.setSpan(StyleSpan(Typeface.ITALIC), i, i + term.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }

    heading("Phone → Watch")
    bullet(
      "The phone reads your due cards from AnkiDroid and pushes the selected decks to the watch " +
        "(compressed, over Bluetooth). Only the decks you check are synced."
    )
    bullet(
      "This happens when you tap \"Sync Due Cards to Watch\", automatically in the background " +
        "(if enabled), and when the watch requests a deck."
    )

    heading("Reviewing on the watch")
    bullet(
      "You review offline on the watch. Each grade is buffered locally, nothing is sent per-card."
    )
    bullet(
      "Scheduling mirrors Anki within the session: a card graded onto a learning step (a sub-day " +
        "interval, e.g. Again always and Hard/Good while it is still in learning) goes back into " +
        "the queue so you relearn it before the deck is done. A day-or-more interval graduates the " +
        "card out. Progress counts graduated cards, so you can't finish without clearing the lapses."
    )
    bullet(
      "The real schedule is still computed by AnkiDroid from the grades you send; the in-session " +
        "repeats are a local approximation for offline use."
    )

    heading("Watch → Phone → AnkiDroid")
    bullet(
      "Buffered grades are sent back to the phone when a session finishes, when you tap " +
        "\"Sync N reviews\", or on the next background sync."
    )
    bullet(
      "The phone writes them into AnkiDroid's scheduler (the same as answering in AnkiDroid), then " +
        "tells the watch to clear its buffer."
    )

    heading("Read-only mode")
    bullet(
      "When enabled (Settings), the phone accepts the watch's reviews and clears the buffer but " +
        "does not change AnkiDroid, useful for testing without affecting your scheduling."
    )

    emphasize("so you can't finish without clearing the lapses")
    emphasize("a local approximation for offline use")
    return sb
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

  /** Material 3 settings dialog opened from the top-bar gear. */
  private fun showSettingsDialog() {
    val pad = (24 * resources.displayMetrics.density).toInt()
    val content =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
      }

    val mockSwitch =
      com.google.android.material.materialswitch.MaterialSwitch(this).apply {
        text = "Force Mock Mode (Demo / Testing)"
        isChecked = forceMock()
      }
    content.addView(mockSwitch)

    val readOnlySwitch =
      com.google.android.material.materialswitch.MaterialSwitch(this).apply {
        text = "Read-only mode (don't write reviews back)"
        isChecked = readOnly()
        setPadding(0, pad / 2, 0, 0)
      }
    content.addView(readOnlySwitch)

    val autoSyncSwitch =
      com.google.android.material.materialswitch.MaterialSwitch(this).apply {
        text = "Auto-sync decks in background"
        isChecked = AutoSync.isEnabled(this@MainActivity)
        setPadding(0, pad / 2, 0, 0)
      }
    content.addView(autoSyncSwitch)

    val intervalLabel =
      com.google.android.material.textview.MaterialTextView(this).apply {
        textSize = 16f
        setPadding(0, pad / 2, 0, 0)
      }
    content.addView(intervalLabel)

    val intervalSlider =
      com.google.android.material.slider.Slider(this).apply {
        valueFrom = AutoSync.MIN_INTERVAL_HOURS.toFloat()
        valueTo = AutoSync.MAX_INTERVAL_HOURS.toFloat()
        stepSize = 1f
        value = AutoSync.intervalHours(this@MainActivity).toFloat()
      }

    fun updateIntervalLabel(hours: Int) {
      intervalLabel.text = "Auto-sync every: ${hours}h"
    }
    updateIntervalLabel(intervalSlider.value.toInt())
    intervalSlider.addOnChangeListener { _, value, _ -> updateIntervalLabel(value.toInt()) }

    // Dim the interval control when auto-sync is off, and keep it in sync with the toggle.
    fun refreshIntervalEnabled(enabled: Boolean) {
      intervalLabel.isEnabled = enabled
      intervalSlider.isEnabled = enabled
    }
    refreshIntervalEnabled(autoSyncSwitch.isChecked)
    autoSyncSwitch.setOnCheckedChangeListener { _, checked -> refreshIntervalEnabled(checked) }
    content.addView(intervalSlider)

    val capLabel =
      com.google.android.material.textview.MaterialTextView(this).apply {
        textSize = 16f
        setPadding(0, pad, 0, 0)
      }
    content.addView(capLabel)

    val capSlider =
      com.google.android.material.slider.Slider(this).apply {
        valueFrom = PREPUSH_MIN.toFloat()
        valueTo = PREPUSH_MAX.toFloat()
        stepSize = 5f
        value = prePushCap().coerceIn(PREPUSH_MIN, PREPUSH_MAX).toFloat()
      }

    fun updateCapLabel(v: Int) {
      capLabel.text = "Decks to pre-push on sync: $v"
    }
    updateCapLabel(capSlider.value.toInt())
    capSlider.addOnChangeListener { _, value, _ -> updateCapLabel(value.toInt()) }
    content.addView(capSlider)

    com.google.android.material.dialog
      .MaterialAlertDialogBuilder(this)
      .setTitle("Settings")
      .setView(content)
      .setPositiveButton("Done") { _, _ ->
        settings
          .edit()
          .putBoolean(KEY_FORCE_MOCK, mockSwitch.isChecked)
          .putBoolean(KEY_READ_ONLY, readOnlySwitch.isChecked)
          .putInt(KEY_PREPUSH_CAP, capSlider.value.toInt())
          .apply()
        // Persists the flag + interval and (re)schedules the WorkManager job.
        AutoSync.apply(this, autoSyncSwitch.isChecked, intervalSlider.value.toInt())
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  /**
   * Debug info about the app, the AnkiDroid connection, the current settings, and the watch link,
   * shown when the status line is tapped. Grouped into sections and copyable for bug reports. The
   * cheap facts are gathered synchronously; the selected-deck ContentProvider query and the
   * connected-watch list are filled in asynchronously so the dialog opens instantly.
   */
  private fun showConnectionInfoDialog() {
    val available = vm.bridge.isAnkiDroidAvailable()
    val permGranted = checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED
    val notifGranted =
      android.os.Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
    val deckPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    val selectedCount = vm.allDecks.count { deckPrefs.getBoolean("deck_${it.first}", true) }

    val lines =
      mutableListOf(
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
        "   Selected deck: checking…",
        "   Decks visible: ${vm.allDecks.size}",
        "   Decks selected to sync: $selectedCount / ${vm.allDecks.size}",
        "",
        "▸ Settings",
        "   Force mock mode: ${if (forceMock()) "on" else "off"}",
        "   Read-only mode: ${if (readOnly()) "on" else "off"}",
        "   Auto-sync (background): ${
          if (AutoSync.isEnabled(this)) "every ${AutoSync.intervalHours(this)}h" else "off"
        }",
        "   Pre-push cap: ${prePushCap()} decks",
        "   Notifications: ${if (notifGranted) "granted" else "denied"}",
        "",
        "▸ Watch link",
        "   Connected watches: checking…",
      )

    // Update the value on the "label:" line in place, preserving section grouping.
    fun setLine(label: String, value: String) {
      val i = lines.indexOfFirst { it.trimStart().startsWith(label) }
      if (i >= 0) lines[i] = "   $label $value"
    }

    val dialog =
      com.google.android.material.dialog
        .MaterialAlertDialogBuilder(this)
        .setTitle("Connection info")
        .setMessage(lines.joinToString("\n"))
        .setPositiveButton("Close", null)
        // Copy reads the current lines, so it includes whatever the async fill-in has resolved.
        .setNeutralButton("Copy") { _, _ -> copyDebugInfo(lines.joinToString("\n")) }
        .show()

    // The selected deck (a ContentProvider query) and the connected-watch list are async; resolve
    // them off the main thread, then refresh the dialog text once.
    lifecycleScope.launch {
      val selectedDeck =
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
      val watches =
        try {
          com.google.android.gms.wearable.Wearable.getNodeClient(this@MainActivity)
            .connectedNodes
            .await()
            .joinToString(", ") { node ->
              node.displayName + if (node.isNearby) " (nearby)" else ""
            }
            .ifEmpty { "none" }
        } catch (e: Exception) {
          "error: ${e.message}"
        }
      setLine("Selected deck:", selectedDeck)
      setLine("Connected watches:", watches)
      dialog.setMessage(lines.joinToString("\n"))
    }
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
