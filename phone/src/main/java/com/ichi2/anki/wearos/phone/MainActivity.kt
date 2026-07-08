package com.ichi2.anki.wearos.phone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uniffi.wearos_core.serializeDueBatch

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
  companion object {
    private const val TAG = "PhoneMainActivity"
    private const val PERMISSION_ANKIDROID = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val PERMISSION_REQUEST_CODE = 420
    private const val PATH_DUE_BATCH = "/due_batch"
    private const val KEY_BATCH_BYTES = "batch_bytes"

    // Pre-push at most this many non-empty decks on a manual sync; scan at most this many looking
    // for them so a large collection doesn't make Sync crawl. The rest download on demand.
    private const val MAX_PREPUSH_DECKS = 15
    private const val MAX_SCAN_DECKS = 60
  }

  private lateinit var bridge: AnkiDroidBridge
  private lateinit var statusText: TextView
  private lateinit var syncButton: Button
  private lateinit var progressBar: ProgressBar
  private lateinit var mockModeSwitch: com.google.android.material.switchmaterial.SwitchMaterial
  private lateinit var deckListLayout: LinearLayout
  private var allDecks: List<Pair<Long, String>> = emptyList()
  private val scope = CoroutineScope(Dispatchers.Main)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bridge = AnkiDroidBridge(this)

    // Set up a simple vertical layout
    val rootLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(64, 64, 64, 64)
      }

    val titleText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "AnkiDroid WearOS Companion"
        textSize = 24f
        setPadding(0, 0, 0, 32)
      }
    rootLayout.addView(titleText)

    statusText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "Checking permissions..."
        textSize = 16f
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(statusText)

    // Switch to toggle Force Mock Mode for testing
    mockModeSwitch =
      com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
        text = "Force Mock Mode (Demo / Testing)"
        isChecked = !bridge.isAnkiDroidAvailable() // Default true if real app is missing
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(mockModeSwitch)

    // Scrollable container for the checkboxes for our decks list
    val scrollView =
      ScrollView(this).apply {
        layoutParams =
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, 0, 0, 48)
          }
      }
    deckListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scrollView.addView(deckListLayout)
    rootLayout.addView(scrollView)

    // Horizontal Progress Bar for sync visual feedback
    progressBar =
      com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        visibility = View.GONE
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(progressBar)

    syncButton =
      com.google.android.material.button.MaterialButton(this).apply {
        text = "Sync Due Cards to Watch"
        isEnabled = false
        setOnClickListener { triggerSyncToWatch() }
      }
    rootLayout.addView(syncButton)

    setContentView(rootLayout)

    checkPermissions()
  }

  private fun checkPermissions() {
    if (checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED) {
      onPermissionsGranted()
    } else {
      statusText.text = "Permission required to query AnkiDroid cards."
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
        onPermissionsGranted()
      } else {
        statusText.text = "Permission Denied. Cannot sync cards."
        Toast.makeText(this, "Permission required to access cards!", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun onPermissionsGranted() {
    val available = bridge.isAnkiDroidAvailable()
    val providerStatus =
      if (available) {
        "AnkiDroid App connected successfully."
      } else {
        "AnkiDroid not found. Running in standalone Mock Mode."
      }
    statusText.text = "Permissions granted.\nStatus: $providerStatus"
    syncButton.isEnabled = true
    populateDeckSelector()
  }

  private fun populateDeckSelector() {
    allDecks = bridge.fetchAllDecks()
    renderDeckTree()
  }

  /** (Re)draw the deck selector as a folder tree built from the Anki `::` deck-name hierarchy. */
  private fun renderDeckTree() {
    deckListLayout.removeAllViews()
    val title =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "Select Decks / Folders to Sync with Watch:"
        textSize = 16f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 16, 0, 16)
      }
    deckListLayout.addView(title)

    val prefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    val root = buildDeckTree(allDecks)
    for (child in root.children.values) {
      addDeckNodeViews(child, 0, prefs)
    }
  }

  private fun addDeckNodeViews(
    node: DeckNode,
    depth: Int,
    prefs: android.content.SharedPreferences,
  ) {
    val indent = 32 + depth * 56
    if (node.children.isNotEmpty()) {
      // Folder row: one checkbox toggles every deck nested under this path (itself included).
      val deckIds = mutableListOf<Long>().also { collectDeckIds(node, it) }
      val allChecked = deckIds.isNotEmpty() && deckIds.all { prefs.getBoolean("deck_$it", true) }
      val folder =
        com.google.android.material.checkbox.MaterialCheckBox(this).apply {
          text = "📁 ${node.name}"
          setTypeface(null, android.graphics.Typeface.BOLD)
          isChecked = allChecked
          setPadding(indent, 12, 0, 12)
          setOnCheckedChangeListener { _, checked ->
            val editor = prefs.edit()
            for (id in deckIds) editor.putBoolean("deck_$id", checked)
            editor.apply()
            renderDeckTree()
          }
        }
      deckListLayout.addView(folder)
      for (child in node.children.values) {
        addDeckNodeViews(child, depth + 1, prefs)
      }
    } else {
      val id = node.deckId ?: return
      val cb =
        com.google.android.material.checkbox.MaterialCheckBox(this).apply {
          text = node.name
          isChecked = prefs.getBoolean("deck_$id", true)
          setPadding(indent, 8, 0, 8)
          setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("deck_$id", checked).apply()
          }
        }
      deckListLayout.addView(cb)
    }
  }

  private fun triggerSyncToWatch() {
    scope.launch {
      try {
        syncButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 10
        statusText.text = "Syncing available decks list..."
        delay(200)

        // 1. Fetch all decks and sync to watch (filtering based on checked deck preferences)
        val decks = bridge.fetchAllDecks()
        val sharedPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
        val filteredDecks = decks.filter { sharedPrefs.getBoolean("deck_${it.first}", true) }
        val decksStr = filteredDecks.joinToString("\n") { "${it.first}:${it.second}" }
        val deckBytes = decksStr.toByteArray(Charsets.UTF_8)

        val dataClient = Wearable.getDataClient(this@MainActivity)
        val putDecksReq =
          PutDataMapRequest.create("/decks")
            .apply {
              dataMap.putByteArray("decks_bytes", deckBytes)
              setUrgent()
            }
            .asPutDataRequest()
        dataClient.putDataItem(putDecksReq).await()

        progressBar.progress = 30
        statusText.text = "Fetching due cards..."
        delay(200)

        // 2. Pre-push the due batches for selected decks so switching to them is instant and works
        //    offline. Only decks that actually have due cards are pushed; the rest download on
        //    demand when tapped on the watch. Capped so a large collection keeps sync responsive.
        val isForced = mockModeSwitch.isChecked
        val renderer = CardBitmapRenderer(this@MainActivity)
        var pushed = 0
        var totalCards = 0
        var scanned = 0
        for ((deckId, deckName) in filteredDecks) {
          if (pushed >= MAX_PREPUSH_DECKS || scanned >= MAX_SCAN_DECKS) break
          scanned++
          val batch =
            withContext(Dispatchers.IO) {
              bridge.fetchDueBatch(deckId = deckId, limit = 20, forceMock = isForced)
            }
          if (batch.cards.isEmpty()) continue

          val bytes = serializeDueBatch(batch)
          val putDataReq =
            PutDataMapRequest.create("$PATH_DUE_BATCH/$deckId")
              .apply {
                dataMap.putByteArray(KEY_BATCH_BYTES, gzip(bytes))
                // Vary the item so the watch always re-receives it on a manual sync.
                dataMap.putLong("synced_at", System.currentTimeMillis())
                setUrgent()
              }
              .asPutDataRequest()
          dataClient.putDataItem(putDataReq).await()

          // Pre-render and sync bitmaps independently to avoid exceeding the 100 KB limit
          renderer.syncBitmapsForBatch(batch)

          pushed++
          totalCards += batch.cards.size
          progressBar.progress = 30 + 65 * pushed / MAX_PREPUSH_DECKS
          statusText.text = "Pushed $pushed decks ($totalCards cards)..."
          Log.d(TAG, "Pushed deck $deckId ('$deckName') with ${batch.cards.size} cards.")
        }

        progressBar.progress = 100
        val remaining = filteredDecks.size - scanned
        val extra = if (remaining > 0) " Others download on demand." else ""
        statusText.text = "Sync Complete! Pushed $pushed decks ($totalCards cards).$extra"
        Log.i(TAG, "Synced $pushed decks / $totalCards cards to watch.$extra")

        // Maintain success visual before hiding
        delay(1500)
        progressBar.visibility = View.GONE
        progressBar.progress = 0
      } catch (e: Exception) {
        Log.e(TAG, "Sync failed: ${e.message}", e)
        statusText.text = "Sync Failed: ${e.message}"
        progressBar.visibility = View.GONE
        Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
      } finally {
        syncButton.isEnabled = true
      }
    }
  }
}

/** A node in the deck hierarchy parsed from Anki `::`-separated deck names. */
class DeckNode(val name: String) {
  var deckId: Long? = null
  val children = sortedMapOf<String, DeckNode>()
}

/** Build a folder tree from flat `parent::child` deck names. */
private fun buildDeckTree(decks: List<Pair<Long, String>>): DeckNode {
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
private fun collectDeckIds(node: DeckNode, out: MutableList<Long>) {
  node.deckId?.let { out.add(it) }
  for (child in node.children.values) collectDeckIds(child, out)
}
