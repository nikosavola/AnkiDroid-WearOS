package com.ichi2.anki.wearos.phone

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.serializeDueBatch

class MainActivity : Activity() {
  companion object {
    private const val TAG = "PhoneMainActivity"
    private const val PERMISSION_ANKIDROID = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val PERMISSION_REQUEST_CODE = 420
    private const val PATH_DUE_BATCH = "/due_batch"
    private const val KEY_BATCH_BYTES = "batch_bytes"
  }

  private lateinit var bridge: AnkiDroidBridge
  private lateinit var statusText: TextView
  private lateinit var syncButton: Button
  private lateinit var progressBar: ProgressBar
  private lateinit var mockModeSwitch: Switch
  private lateinit var deckListLayout: LinearLayout
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
      TextView(this).apply {
        text = "AnkiDroid WearOS Companion"
        textSize = 24f
        setPadding(0, 0, 0, 32)
      }
    rootLayout.addView(titleText)

    statusText =
      TextView(this).apply {
        text = "Checking permissions..."
        textSize = 16f
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(statusText)

    // Switch to toggle Force Mock Mode for testing
    mockModeSwitch =
      Switch(this).apply {
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
      ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        visibility = View.GONE
        setPadding(0, 0, 0, 48)
      }
    rootLayout.addView(progressBar)

    syncButton =
      Button(this).apply {
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
    deckListLayout.removeAllViews()
    val title =
      TextView(this).apply {
        text = "Select Decks to Sync with Watch:"
        textSize = 16f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, 16, 0, 16)
      }
    deckListLayout.addView(title)

    val decks = bridge.fetchAllDecks()
    val sharedPrefs = getSharedPreferences("deck_sync_prefs", Context.MODE_PRIVATE)

    for ((id, name) in decks) {
      val cb =
        CheckBox(this).apply {
          text = name
          // Default to true if not explicitly configured yet
          isChecked = sharedPrefs.getBoolean("deck_$id", true)
          setOnCheckedChangeListener { _, checked ->
            sharedPrefs.edit().putBoolean("deck_$id", checked).apply()
          }
          setPadding(0, 12, 0, 12)
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
        statusText.text = "Connecting to database..."
        delay(200)

        // 2. Fetch from bridge (deckId = 1 as default/mock)
        val isForced = mockModeSwitch.isChecked
        val batch = bridge.fetchDueBatch(deckId = 1L, limit = 20, forceMock = isForced)
        progressBar.progress = 50
        statusText.text = "Fetched ${batch.cards.size} cards from '${batch.deckName}'."
        Log.d(TAG, "Fetched ${batch.cards.size} due cards.")
        delay(200)

        statusText.text = "Serializing batch via Rust core..."
        // 3. Serialize using our Rust core FFI (returns ByteArray directly)
        val bytes = serializeDueBatch(batch)
        progressBar.progress = 80
        Log.d(TAG, "Serialized batch into ${bytes.size} bytes.")
        delay(200)

        statusText.text = "Broadcasting data to Wear OS Data Layer..."
        // 4. Sync over DataClient
        val putDataReq =
          PutDataMapRequest.create(PATH_DUE_BATCH)
            .apply {
              dataMap.putByteArray(KEY_BATCH_BYTES, bytes)
              setUrgent()
            }
            .asPutDataRequest()

        dataClient.putDataItem(putDataReq).await()
        progressBar.progress = 100
        statusText.text = "Sync Complete! Successfully sent ${batch.cards.size} cards to Watch."
        Log.i(TAG, "Successfully synced batch containing ${batch.cards.size} cards to watch!")

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
