package fi.nikosavola.ankidroid.wearos.phone

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import uniffi.wearos_core.deserializeAnswers

class WearSyncService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWearSyncService"
        private const val PATH_ANSWERS = "/answers"
        private const val KEY_ANSWERS_BYTES = "answers_bytes"
        private const val PATH_CLEAR_ANSWERS = "/clear_answers"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var bridge: AnkiDroidBridge

    override fun onCreate() {
        super.onCreate()
        bridge = AnkiDroidBridge(applicationContext)
        Log.d(TAG, "WearSyncService created on Phone Companion.")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged received ${dataEvents.count} events.")
        
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val uri = event.dataItem.uri
                val path = uri.path
                Log.d(TAG, "Data layer item changed: $path")

                if (path == PATH_ANSWERS) {
                    val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
                    val bytes = dataMapItem.dataMap.getByteArray(KEY_ANSWERS_BYTES)

                    if (bytes != null) {
                        scope.launch {
                            processAnswers(bytes)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processAnswers(bytes: ByteArray) {
        try {
            Log.d(TAG, "Deserializing watch answers via Rust core...")
            val answers = deserializeAnswers(bytes)
            Log.i(TAG, "Received ${answers.size} answers from Wear OS watch.")

            if (answers.isNotEmpty()) {
                val successCount = bridge.writeAnswersBack(answers)
                Log.i(TAG, "Successfully wrote back $successCount / ${answers.size} answers to AnkiDroid database.")

                if (successCount > 0) {
                    // Send confirmation message to clear these IDs on the watch SQLite cache
                    val idsString = answers.map { it.id }.joinToString(",")
                    val nodeClient = Wearable.getNodeClient(this@WearSyncService)
                    val messageClient = Wearable.getMessageClient(this@WearSyncService)
                    
                    val nodes = nodeClient.connectedNodes.await()
                    Log.d(TAG, "Broadcasting clear_answers to ${nodes.size} nodes.")
                    for (node in nodes) {
                        try {
                            messageClient.sendMessage(node.id, PATH_CLEAR_ANSWERS, idsString.toByteArray()).await()
                            Log.d(TAG, "Sent clear_answers confirmation to node: ${node.displayName}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send clear_answers to node ${node.id}: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process answers: ${e.message}", e)
        }
    }
}
