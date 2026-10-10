package fi.nikosavola.ankidroid.wearos.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
  // UI state + light orchestration live in the ViewModel (survives config changes); this Activity is
  // just the framework boundary (permissions, receiver, service start, clipboard).
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

  private val cardAccessRequest =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        // onResume may already have picked the grant up.
        if (vm.cardAccess != CardAccess.GRANTED) onCardAccessGranted()
      } else {
        // rationale=false right after a denial means "don't ask again" (or no such permission).
        vm.cardAccess =
          when {
            !ankiDroidPermissionDefined() -> CardAccess.ANKIDROID_MISSING
            shouldShowRequestPermissionRationale(ANKIDROID_PERMISSION) -> CardAccess.DENIED
            else -> CardAccess.BLOCKED
          }
        vm.setStatus("Permission denied — can't read cards", StatusTone.ERROR)
      }
    }

  // Sync still runs without it; the request only matters for the progress notification.
  private val notificationRequest =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  private val actions by lazy {
    PhoneActions(
      onSync = ::triggerSyncToWatch,
      onRequestCardAccess = ::requestCardAccess,
      onOpenAppSettings = ::openAppSettings,
      onRefreshDecks = ::forceSyncWithAnkiDroid,
      onCopyReport = ::copyDebugInfo,
      onStudyDeck = ::studyInAnkiDroid,
      onBrowseDeck = ::browseInAnkiDroid,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { AnkiPhoneTheme { PhoneScreen(vm, actions) } }

    ContextCompat.registerReceiver(
      this,
      syncProgressReceiver,
      IntentFilter(SyncService.ACTION_PROGRESS),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    // A recreated Activity keeps the VM's access state; only a fresh launch prompts.
    if (savedInstanceState == null || vm.cardAccess == CardAccess.UNKNOWN) {
      requestCardAccess()
      // Schedule (or cancel) the recurring background sync per the saved preference. WorkManager
      // keeps an already-running schedule (KEEP), so this is safe on every launch.
      lifecycleScope.launch(Dispatchers.IO) { AutoSync.applyFromSettings(applicationContext) }
    }
  }

  override fun onResume() {
    super.onResume()
    // A background auto-sync may have run while we were away; refresh the relative time.
    vm.updateLastSyncLabel()
    // Back from the app's system settings page with access now granted.
    if (vm.cardAccess != CardAccess.GRANTED && hasCardAccess()) onCardAccessGranted()
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(syncProgressReceiver)
  }

  private fun hasCardAccess() =
    checkSelfPermission(ANKIDROID_PERMISSION) == PackageManager.PERMISSION_GRANTED

  private fun ankiDroidPermissionDefined(): Boolean =
    try {
      packageManager.getPermissionInfo(ANKIDROID_PERMISSION, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  private fun onCardAccessGranted() {
    vm.cardAccess = CardAccess.GRANTED
    vm.loadDecks()
  }

  private fun requestCardAccess() {
    when {
      hasCardAccess() -> {
        onCardAccessGranted()
      }
      !ankiDroidPermissionDefined() -> {
        vm.cardAccess = CardAccess.ANKIDROID_MISSING
        vm.setStatus("AnkiDroid not installed", StatusTone.ERROR)
      }
      vm.cardAccess == CardAccess.BLOCKED -> {
        openAppSettings()
      }
      else -> {
        vm.setStatus("Waiting for card access permission…", StatusTone.NEUTRAL)
        cardAccessRequest.launch(ANKIDROID_PERMISSION)
      }
    }
  }

  private fun openAppSettings() {
    startActivity(
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    )
  }

  // AnkiDroid's own home-screen deck shortcut: selects the deck and opens its reviewer.
  private fun studyInAnkiDroid(deckId: DeckId) =
    openInAnkiDroid(
      Intent()
        .setClassName(ANKIDROID_PACKAGE, "com.ichi2.anki.IntentHandler")
        .putExtra("EXTRA_DECK_ID", deckId.value)
    )

  private fun browseInAnkiDroid(deckName: String) =
    openInAnkiDroid(
      Intent(
          Intent.ACTION_VIEW,
          Uri.Builder()
            .scheme("anki")
            .authority("x-callback-url")
            .path("/browser")
            .appendQueryParameter("search", deckSearch(deckName))
            .build(),
        )
        .setPackage(ANKIDROID_PACKAGE)
    )

  private fun openInAnkiDroid(intent: Intent) {
    try {
      startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(this, "This AnkiDroid version can't open that", Toast.LENGTH_SHORT).show()
    }
  }

  /**
   * Re-query the deck list from AnkiDroid's ContentProvider and redraw. Note: AnkiDroid caches its
   * collection in-process, so a brand-new deck may only appear here after AnkiDroid itself reloads.
   */
  private fun forceSyncWithAnkiDroid() {
    if (!hasCardAccess()) {
      requestCardAccess()
      return
    }
    Toast.makeText(this, "Refreshing decks from AnkiDroid…", Toast.LENGTH_SHORT).show()
    vm.loadDecks()
  }

  /**
   * Kick off the sync in [SyncService] (a foreground service) so it keeps running with a progress
   * notification even if the app is backgrounded. Progress is mirrored back into the in-app bar via
   * [syncProgressReceiver] while the Activity is alive.
   */
  private fun triggerSyncToWatch() {
    if (!vm.syncEnabled) return
    // Notification permission is runtime-gated on API 33+; ask so the background progress shows.
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    vm.onSyncStarting()
    ContextCompat.startForegroundService(this, Intent(this, SyncService::class.java))
  }

  /** Copy the debug report to the clipboard so it can be pasted into a bug report. */
  private fun copyDebugInfo(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AnkiDroid Wear debug info", text))
    Toast.makeText(this, "Copied debug info", Toast.LENGTH_SHORT).show()
  }
}
