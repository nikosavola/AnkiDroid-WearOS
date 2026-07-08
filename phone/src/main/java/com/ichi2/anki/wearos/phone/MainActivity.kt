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
import com.google.android.gms.wearable.Asset
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
    // See WearSyncService: batch goes over an Asset to dodge the ~100KB DataItem cap.
    private const val KEY_BATCH_ASSET = "batch_asset"

    // Pre-push at most this many non-empty decks on a manual sync (default; overridable in
    // Settings); scan at most this many looking for them so a large collection doesn't make Sync
    // crawl. The rest download on demand.
    private const val DEFAULT_PREPUSH_DECKS = 15
    private const val MAX_SCAN_DECKS = 60

    // User settings, persisted across launches.
    private const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_FORCE_MOCK = "force_mock"
    private const val KEY_PREPUSH_CAP = "prepush_cap"
    private const val PREPUSH_MIN = 5
    private const val PREPUSH_MAX = 30
  }

  private lateinit var bridge: AnkiDroidBridge
  private lateinit var statusText: TextView
  private lateinit var syncButton: Button
  private lateinit var progressBar: ProgressBar
  private lateinit var deckListLayout: LinearLayout
  private var allDecks: List<Pair<Long, String>> = emptyList()
  // Folder paths currently expanded in the deck selector. Empty = everything collapsed, so the
  // list opens compact (just the top-level folders) and the user drills in with [+].
  private val expandedFolders = mutableSetOf<String>()
  private val scope = CoroutineScope(Dispatchers.Main)

  private val settings by lazy { getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

  private fun forceMock(): Boolean =
    settings.getBoolean(KEY_FORCE_MOCK, !bridge.isAnkiDroidAvailable())

  private fun prePushCap(): Int = settings.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bridge = AnkiDroidBridge(this)

    // Set up a simple vertical layout
    val rootLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(64, 64, 64, 64)
      }
    // targetSdk 35 draws edge-to-edge on Android 15, so keep content (incl. the gear) clear of the
    // status/nav bars by adding the system-bar insets on top of the base padding.
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
      val bars =
        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
      v.setPadding(64, 64 + bars.top, 64, 64 + bars.bottom)
      insets
    }

    // Top bar: title on the left, a Material gear on the right that opens Settings.
    val topBar =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 32)
      }
    val titleText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "AnkiDroid WearOS Companion"
        textSize = 24f
        layoutParams =
          LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    topBar.addView(titleText)
    val settingsButton =
      android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_settings)
        contentDescription = "Settings"
        background =
          resolveAttrDrawable(android.R.attr.selectableItemBackgroundBorderless)
        setOnClickListener { showSettingsDialog() }
      }
    topBar.addView(settingsButton)
    rootLayout.addView(topBar)

    statusText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "Checking permissions..."
        textSize = 16f
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(statusText)

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
      addDeckNodeViews(child, 0, child.name, prefs)
    }
  }

  // Fixed expander column width so leaf checkboxes line up under folder names at the same depth.
  private val expanderWidthPx by lazy { (48 * resources.displayMetrics.density).toInt() }

  private fun addDeckNodeViews(
    node: DeckNode,
    depth: Int,
    path: String,
    prefs: android.content.SharedPreferences,
  ) {
    val indent = 32 + depth * 56
    if (node.children.isNotEmpty()) {
      // Folder row: a [+]/[-] expander plus a checkbox that toggles every deck nested under this
      // path (itself included). Children only render when the folder is expanded.
      val expanded = expandedFolders.contains(path)
      val deckIds = mutableListOf<Long>().also { collectDeckIds(node, it) }
      val allChecked = deckIds.isNotEmpty() && deckIds.all { prefs.getBoolean("deck_$it", true) }

      val row =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = android.view.Gravity.CENTER_VERTICAL
          setPadding(indent, 4, 0, 4)
        }
      // M3 expand affordance: a standard icon button with the expand_more chevron, rotated to
      // point up when the folder is open (the Material 3 idiom, rather than a +/- glyph).
      val expander =
        com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialIconButtonStyle,
          )
          .apply {
            icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_expand_more)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            rotation = if (expanded) 180f else 0f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            layoutParams =
              LinearLayout.LayoutParams(expanderWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            contentDescription = if (expanded) "Collapse ${node.name}" else "Expand ${node.name}"
            setOnClickListener {
              if (expanded) expandedFolders.remove(path) else expandedFolders.add(path)
              renderDeckTree()
            }
          }
      row.addView(expander)
      val folder =
        com.google.android.material.checkbox.MaterialCheckBox(this).apply {
          text = "📁 ${node.name}"
          setTypeface(null, android.graphics.Typeface.BOLD)
          isChecked = allChecked
          setPadding(16, 0, 0, 0)
          setOnCheckedChangeListener { _, checked ->
            val editor = prefs.edit()
            for (id in deckIds) editor.putBoolean("deck_$id", checked)
            editor.apply()
            renderDeckTree()
          }
        }
      row.addView(folder)
      deckListLayout.addView(row)

      if (expanded) {
        for (child in node.children.values) {
          addDeckNodeViews(child, depth + 1, "$path::${child.name}", prefs)
        }
      }
    } else {
      val id = node.deckId ?: return
      // Wrap in a padded row (rather than padding the checkbox) so the box, not just the text,
      // lines up under the sibling folders' checkboxes past the expander column.
      val row =
        LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
          setPadding(indent + expanderWidthPx + 16, 4, 0, 4)
        }
      val cb =
        com.google.android.material.checkbox.MaterialCheckBox(this).apply {
          text = node.name
          isChecked = prefs.getBoolean("deck_$id", true)
          setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("deck_$id", checked).apply()
          }
        }
      row.addView(cb)
      deckListLayout.addView(row)
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
        val isForced = forceMock()
        val prePushCap = prePushCap()
        val renderer = CardBitmapRenderer(this@MainActivity)
        var pushed = 0
        var totalCards = 0
        var scanned = 0
        for ((deckId, deckName) in filteredDecks) {
          if (pushed >= prePushCap || scanned >= MAX_SCAN_DECKS) break
          scanned++
          val batch =
            withContext(Dispatchers.IO) {
              bridge.fetchDueBatch(deckId = deckId, limit = 20, forceMock = isForced)
            }
          if (batch.cards.isEmpty()) continue

          val cleanedCards =
            batch.cards.map { card ->
              card.copy(
                front = AnkiDroidBridge.stripBase64Images(card.front),
                back = AnkiDroidBridge.stripBase64Images(card.back),
              )
            }
          val cleanedBatch = batch.copy(cards = cleanedCards)
          val bytes = serializeDueBatch(cleanedBatch)
          val putDataReq =
            PutDataMapRequest.create("$PATH_DUE_BATCH/$deckId")
              .apply {
                dataMap.putAsset(KEY_BATCH_ASSET, Asset.createFromBytes(gzip(bytes)))
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
          progressBar.progress = 30 + 65 * pushed / prePushCap
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

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Settings")
      .setView(content)
      .setPositiveButton("Done") { _, _ ->
        settings
          .edit()
          .putBoolean(KEY_FORCE_MOCK, mockSwitch.isChecked)
          .putInt(KEY_PREPUSH_CAP, capSlider.value.toInt())
          .apply()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  /** Resolve a theme attribute that points at a drawable (e.g. a ripple background). */
  private fun resolveAttrDrawable(attr: Int): android.graphics.drawable.Drawable? {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
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
