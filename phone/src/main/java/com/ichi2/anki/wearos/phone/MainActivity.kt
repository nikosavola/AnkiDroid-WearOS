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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.widget.addTextChangedListener
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

    // Overflow menu item ids.
    private const val MENU_HELP = 1
    private const val MENU_SETTINGS = 2
    private const val MENU_FORCE_SYNC = 3
    private const val MENU_GRANT_PERMS = 4

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

  private lateinit var bridge: AnkiDroidBridge
  private lateinit var statusText: TextView
  private lateinit var syncStatusText: TextView
  private lateinit var syncButton: Button
  private lateinit var progressBar: ProgressBar
  private lateinit var deckListLayout: LinearLayout
  private var allDecks: List<Pair<Long, String>> = emptyList()
  // Folder paths currently expanded in the deck selector. Empty = everything collapsed, so the
  // list opens compact (just the top-level folders) and the user drills in with [+].
  private val expandedFolders = mutableSetOf<String>()
  // Case-insensitive deck-name filter from the search box; blank shows the full tree.
  private var deckFilter: String = ""
  private lateinit var lastSyncText: TextView
  private lateinit var sectionHeader: TextView
  // Best-effort per-deck due totals (deckId -> count); empty when AnkiDroid doesn't expose them.
  private var deckCounts: Map<Long, Int> = emptyMap()

  // Mirrors SyncService progress into the in-app status line + bar while the Activity is alive.
  private val syncProgressReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != SyncService.ACTION_PROGRESS) return
        val text = intent.getStringExtra(SyncService.EXTRA_TEXT).orEmpty()
        val percent = intent.getIntExtra(SyncService.EXTRA_PERCENT, 0)
        val done = intent.getBooleanExtra(SyncService.EXTRA_DONE, false)
        val error = intent.getBooleanExtra(SyncService.EXTRA_ERROR, false)

        syncStatusText.visibility = View.VISIBLE
        syncStatusText.text = text
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = percent

        if (done) {
          syncButton.isEnabled = true
          if (error) {
            progressBar.visibility = View.GONE
          } else {
            updateLastSyncLabel()
            // Hold the "Done" state briefly, then clear.
            syncStatusText.postDelayed(
              {
                progressBar.visibility = View.GONE
                syncStatusText.visibility = View.GONE
              },
              1500,
            )
          }
        }
      }
    }

  private val settings by lazy { getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE) }

  private fun forceMock(): Boolean =
    settings.getBoolean(KEY_FORCE_MOCK, !bridge.isAnkiDroidAvailable())

  private fun prePushCap(): Int = settings.getInt(KEY_PREPUSH_CAP, DEFAULT_PREPUSH_DECKS)

  /**
   * Inset the root past the status/nav bars AND horizontal display cutouts, and pad the bottom for
   * the IME so the on-screen keyboard doesn't cover the search box or the sync button.
   */
  private fun applyEdgeToEdgeInsets(root: View) {
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars =
        insets.getInsets(
          androidx.core.view.WindowInsetsCompat.Type.systemBars() or
            androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        )
      val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
      v.setPadding(
        64 + bars.left,
        64 + bars.top,
        64 + bars.right,
        64 + maxOf(bars.bottom, ime.bottom),
      )
      insets
    }
  }

  private fun readOnly(): Boolean = settings.getBoolean(KEY_READ_ONLY, false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Opt into edge-to-edge explicitly (not just implicitly via targetSdk 35) so the intent is
    // clear
    // and the system bars get proper contrast scrims.
    enableEdgeToEdge()
    bridge = AnkiDroidBridge(this)

    // Set up a simple vertical layout
    val rootLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(64, 64, 64, 64)
      }
    applyEdgeToEdgeInsets(rootLayout)

    val density = resources.displayMetrics.density
    rootLayout.addView(buildTopBar(density))

    statusText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "Checking permissions…"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        // Clickable: a trailing info icon + ripple signal that tapping shows connection details.
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
        compoundDrawablePadding = 12
        isClickable = true
        isFocusable = true
        background = resolveAttrDrawable(android.R.attr.selectableItemBackground)
        setPadding(8, 16, 8, 32)
        setOnClickListener { showConnectionInfoDialog() }
      }
    rootLayout.addView(statusText)

    // Bold section header for the deck list (M3 title role, primary color).
    sectionHeader =
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "Decks to sync"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
          )
        )
        setPadding(0, 0, 0, 12)
      }
    rootLayout.addView(sectionHeader)

    // "Last synced …" caption (relative time), refreshed on launch and after each sync.
    lastSyncText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
          )
        )
        setPadding(0, 0, 0, 12)
      }
    rootLayout.addView(lastSyncText)
    updateLastSyncLabel()

    rootLayout.addView(buildDeckFilterControls(density))

    // Scrollable container for the checkboxes for our decks list
    val scrollView =
      ScrollView(this).apply {
        layoutParams =
          LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(0, 0, 0, 16)
          }
      }
    deckListLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Stopgap "Expressive motion": animate rows appearing/disappearing on expand/collapse. A
        // true M3 Expressive tree (spring chevron, per-subtree animation) wants the Compose
        // migration — see HANDOFF_TESTING.md; this at least removes the abrupt full redraw.
        layoutTransition = android.animation.LayoutTransition()
      }
    scrollView.addView(deckListLayout)
    rootLayout.addView(scrollView)

    // One-line live sync status, shown right above the progress bar while a sync runs.
    syncStatusText =
      com.google.android.material.textview.MaterialTextView(this).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
          )
        )
        visibility = View.GONE
        setPadding(0, 0, 0, 8)
      }
    rootLayout.addView(syncStatusText)

    // Horizontal Progress Bar for sync visual feedback
    progressBar =
      com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        visibility = View.GONE
        setPadding(0, 0, 0, 32)
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
    if (::lastSyncText.isInitialized) updateLastSyncLabel()
  }

  override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(syncProgressReceiver)
  }

  /**
   * Material 3 large-top-app-bar-style header: a primary-colored overline above a headline title on
   * the left, with a Material icon button on the right that opens Settings (top-aligned so it sits
   * in the corner like a real top-app-bar action when the title wraps).
   */
  private fun buildTopBar(density: Float): View {
    val topBar =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.TOP
        setPadding(0, 0, 0, (24 * density).toInt())
      }

    val titleColumn =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    titleColumn.addView(
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "ANKIDROID"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
          )
        )
        // Wide tracking + medium weight give the caps overline the polished M3 label look.
        letterSpacing = 0.1f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
      }
    )
    titleColumn.addView(
      com.google.android.material.textview.MaterialTextView(this).apply {
        text = "WearOS Companion"
        setTextAppearance(
          com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium
        )
        setTextColor(
          com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
          )
        )
        setPadding(0, (2 * density).toInt(), 0, 0)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
    )
    topBar.addView(titleColumn)

    // One three-dot overflow instead of separate icons: folds settings, the sync-help dialog, a
    // manual AnkiDroid re-query, and a permission re-request into a single uncluttered menu.
    topBar.addView(
      com.google.android.material.button
        .MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle)
        .apply {
          icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_more_vert)
          iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
          iconPadding = 0
          contentDescription = "More options"
          setOnClickListener { showOverflowMenu(it) }
        }
    )
    return topBar
  }

  /**
   * Three-dot overflow: sync help, settings, a manual AnkiDroid deck re-query, permission
   * re-request.
   */
  private fun showOverflowMenu(anchor: View) {
    android.widget.PopupMenu(this, anchor).apply {
      menu.add(0, MENU_HELP, 0, "How syncing works")
      menu.add(0, MENU_SETTINGS, 1, "Settings")
      menu.add(0, MENU_FORCE_SYNC, 2, "Force sync with AnkiDroid")
      menu.add(0, MENU_GRANT_PERMS, 3, "Grant permissions")
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          MENU_HELP -> showSyncHelpDialog()
          MENU_SETTINGS -> showSettingsDialog()
          MENU_FORCE_SYNC -> forceSyncWithAnkiDroid()
          MENU_GRANT_PERMS -> checkPermissions()
          else -> return@setOnMenuItemClickListener false
        }
        true
      }
      show()
    }
  }

  /**
   * Re-query the deck list from AnkiDroid's ContentProvider and redraw. Note: AnkiDroid caches its
   * collection in-process, so a brand-new deck may only appear here after AnkiDroid itself reloads.
   */
  private fun forceSyncWithAnkiDroid() {
    Toast.makeText(this, "Refreshing decks from AnkiDroid…", Toast.LENGTH_SHORT).show()
    onPermissionsGranted()
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

  /** Search box (filters the deck tree by name) plus "Select all/none" bulk controls. */
  private fun buildDeckFilterControls(density: Float): View {
    val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val searchLayout =
      com.google.android.material.textfield.TextInputLayout(this).apply {
        hint = "Search decks"
        layoutParams =
          LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            .apply { setMargins(0, 0, 0, (8 * density).toInt()) }
      }
    searchLayout.addView(
      com.google.android.material.textfield.TextInputEditText(searchLayout.context).apply {
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addTextChangedListener { editable ->
          deckFilter = editable?.toString().orEmpty().trim()
          renderDeckTree()
        }
      }
    )
    container.addView(searchLayout)

    val bulkRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, (8 * density).toInt())
      }
    bulkRow.addView(
      com.google.android.material.button
        .MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        .apply {
          text = "Select all"
          setOnClickListener { setAllDecksSelected(true) }
        }
    )
    bulkRow.addView(
      com.google.android.material.button
        .MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        .apply {
          text = "Select none"
          setOnClickListener { setAllDecksSelected(false) }
          layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
              )
              .apply { setMarginStart((8 * density).toInt()) }
        }
    )
    container.addView(bulkRow)
    return container
  }

  /** Set the top status line concisely, colored by the theme attribute for the current state. */
  private fun setStatus(text: String, colorAttr: Int) {
    statusText.text = text
    statusText.setTextColor(
      com.google.android.material.color.MaterialColors.getColor(statusText, colorAttr)
    )
  }

  private fun checkPermissions() {
    if (checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED) {
      onPermissionsGranted()
    } else {
      setStatus(
        "Waiting for card access permission…",
        com.google.android.material.R.attr.colorOnSurfaceVariant,
      )
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
        setStatus(
          "Permission denied — can't read cards",
          com.google.android.material.R.attr.colorError,
        )
        Toast.makeText(this, "Permission required to access cards!", Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun onPermissionsGranted() {
    syncButton.isEnabled = true
    // The availability probe and the (potentially large) deck query are ContentProvider I/O; keep
    // them off the main thread so launch doesn't jank, then update the UI on the main thread.
    lifecycleScope.launch {
      val available = withContext(Dispatchers.IO) { bridge.isAnkiDroidAvailable() }
      if (available) {
        setStatus("Connected to AnkiDroid", com.google.android.material.R.attr.colorPrimary)
      } else {
        setStatus(
          "Standalone mock mode — AnkiDroid not found",
          com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
      }
      allDecks = withContext(Dispatchers.IO) { bridge.fetchAllDecks() }
      renderDeckTree()
      // Due counts (if the provider exposes them) can be slow; load them after the tree is drawn
      // and re-render to annotate rows.
      deckCounts = withContext(Dispatchers.IO) { bridge.fetchDeckCounts() }
      if (deckCounts.isNotEmpty()) renderDeckTree()
    }
  }

  /** (Re)draw the deck selector as a folder tree built from the Anki `::` deck-name hierarchy. */
  private fun renderDeckTree() {
    deckListLayout.removeAllViews()
    val prefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    updateSelectedCountHeader()
    val decks =
      if (deckFilter.isBlank()) {
        allDecks
      } else {
        allDecks.filter { it.second.contains(deckFilter, ignoreCase = true) }
      }
    if (decks.isEmpty()) {
      deckListLayout.addView(
        com.google.android.material.textview.MaterialTextView(this).apply {
          text = if (deckFilter.isBlank()) "No decks found." else "No decks match \"$deckFilter\"."
          setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
      )
      return
    }
    val root = buildDeckTree(decks)
    for (child in root.children.values) {
      addDeckNodeViews(child, 0, child.name, prefs)
    }
  }

  /** Refresh the "Decks to sync (N/M)" header count without rebuilding the tree. */
  private fun updateSelectedCountHeader() {
    if (!::sectionHeader.isInitialized) return
    val prefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    val selected = allDecks.count { prefs.getBoolean("deck_${it.first}", true) }
    sectionHeader.text = "Decks to sync ($selected/${allDecks.size})"
  }

  /** Check or uncheck every deck at once (bulk select controls above the tree). */
  private fun setAllDecksSelected(selected: Boolean) {
    val prefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    prefs.edit().apply { for ((id, _) in allDecks) putBoolean("deck_$id", selected) }.apply()
    renderDeckTree()
  }

  /** Refresh the "Last synced …" caption from the stored timestamp. */
  private fun updateLastSyncLabel() {
    val ms = settings.getLong(DeckSync.KEY_LAST_SYNC_MS, 0L)
    lastSyncText.text =
      if (ms <= 0L) {
        "Not synced yet"
      } else {
        "Last synced " +
          android.text.format.DateUtils.getRelativeTimeSpanString(
            ms,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
          )
      }
  }

  // Fixed expander column width so leaf checkboxes line up under folder names at the same depth.
  private val expanderWidthPx by lazy { (48 * resources.displayMetrics.density).toInt() }

  /** " · N due" for a node's subtree, or "" when we have no count data for it (never shows 0). */
  private fun dueSuffix(node: DeckNode): String {
    val known = node.deckIds().mapNotNull { deckCounts[it] }
    return if (known.isEmpty()) "" else " · ${known.sum()} due"
  }

  private fun addDeckNodeViews(
    node: DeckNode,
    depth: Int,
    path: String,
    prefs: android.content.SharedPreferences,
  ) {
    val indent = 32 + depth * 56
    if (node.children.isNotEmpty()) {
      // Folder row: a [+]/[-] expander plus a checkbox that toggles every deck nested under this
      // path (itself included). Children only render when the folder is expanded. While a search
      // filter is active, every folder is forced open so matches are visible.
      val expanded = deckFilter.isNotBlank() || expandedFolders.contains(path)
      val deckIds = node.deckIds()
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
        com.google.android.material.button
          .MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle)
          .apply {
            icon =
              androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_expand_more)
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
          text = "📁 ${node.name}${dueSuffix(node)}"
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
          text = "${node.name}${dueSuffix(node)}"
          isChecked = prefs.getBoolean("deck_$id", true)
          setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("deck_$id", checked).apply()
            updateSelectedCountHeader()
          }
        }
      row.addView(cb)
      deckListLayout.addView(row)
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

    syncButton.isEnabled = false
    syncStatusText.visibility = View.VISIBLE
    syncStatusText.text = "Starting sync…"
    progressBar.visibility = View.VISIBLE
    progressBar.isIndeterminate = true

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
    val available = bridge.isAnkiDroidAvailable()
    val permGranted = checkSelfPermission(PERMISSION_ANKIDROID) == PackageManager.PERMISSION_GRANTED
    val notifGranted =
      android.os.Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
    val deckPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)
    val selectedCount = allDecks.count { deckPrefs.getBoolean("deck_${it.first}", true) }

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
        "   Decks visible: ${allDecks.size}",
        "   Decks selected to sync: $selectedCount / ${allDecks.size}",
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
            if (available) bridge.getSelectedDeck().let { "${it.second} (${it.first})" } else "n/a"
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
internal fun buildDeckTree(decks: List<Pair<Long, String>>): DeckNode {
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
internal fun collectDeckIds(node: DeckNode, out: MutableList<Long>) {
  node.deckId?.let { out.add(it) }
  for (child in node.children.values) collectDeckIds(child, out)
}

/** Every real deck id at or below this node — an idiomatic `buildList` wrapper. */
internal fun DeckNode.deckIds(): List<Long> = buildList { collectDeckIds(this@deckIds, this) }
