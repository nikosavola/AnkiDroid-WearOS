package com.ichi2.anki.wearos.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Send [path] to every connected phone node; returns how many received it (0 when none is near). */
internal suspend fun sendToConnectedPhones(
  context: Context,
  path: String,
  payload: ByteArray = ByteArray(0),
): Int {
  val nodes = Wearable.getNodeClient(context).connectedNodes.await()
  val messageClient = Wearable.getMessageClient(context)
  for (node in nodes) messageClient.sendMessage(node.id, path, payload).await()
  return nodes.size
}
