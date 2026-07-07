package fi.nikosavola.ankidroid.wearos.phone

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = AnkiDroidBridge(this)

        // Set up a simple vertical layout
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        val titleText = TextView(this).apply {
            text = "AnkiDroid WearOS Companion"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(titleText)

        statusText = TextView(this).apply {
            text = "Checking permissions..."
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }
        rootLayout.addView(statusText)

        syncButton = Button(this).apply {
            text = "Sync Due Cards to Watch"
            isEnabled = false
            setOnClickListener {
                triggerSyncToWatch()
            }
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
        grantResults: IntArray
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
        val providerStatus = if (available) {
            "AnkiDroid App connected successfully."
        } else {
            "AnkiDroid not found. Running in standalone Mock Mode."
        }
        statusText.text = "Permissions granted.\nStatus: $providerStatus"
        syncButton.isEnabled = true
    }

    private fun triggerSyncToWatch() {
        scope.launch {
            try {
                statusText.text = "Fetching due cards..."
                // 1. Fetch from bridge (deckId = 1 as default/mock)
                val batch = bridge.fetchDueBatch(deckId = 1L, limit = 20)
                Log.d(TAG, "Fetched ${batch.cards.size} due cards.")

                statusText.text = "Serializing batch via Rust core..."
                // 2. Serialize using our Rust core FFI (returns ByteArray directly)
                val bytes = serializeDueBatch(batch)
                Log.d(TAG, "Serialized batch into ${bytes.size} bytes.")

                statusText.text = "Broadcasting data to Wear OS Data Layer..."
                // 3. Put into GMS Wearable Data Layer
                val dataClient = Wearable.getDataClient(this@MainActivity)
                val putDataReq = PutDataMapRequest.create(PATH_DUE_BATCH).apply {
                    dataMap.putByteArray(KEY_BATCH_BYTES, bytes)
                    // Setting as urgent guarantees prompt synchronization
                    setUrgent()
                }.asPutDataRequest()

                dataClient.putDataItem(putDataReq).await()
                
                Log.i(TAG, "Successfully synced batch containing ${batch.cards.size} cards to watch!")
                statusText.text = "Synced successfully!\n\nSent ${batch.cards.size} cards (${bytes.size} bytes) from deck: '${batch.deckName}'."
                Toast.makeText(this@MainActivity, "Synced ${batch.cards.size} cards to Watch!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed: ${e.message}", e)
                statusText.text = "Sync failed: ${e.message}"
                Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
