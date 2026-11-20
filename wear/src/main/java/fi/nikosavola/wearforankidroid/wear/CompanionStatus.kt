package fi.nikosavola.wearforankidroid.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.wear.phone.interactions.PhoneTypeHelper
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Advertised by the phone companion, so the watch can tell whether it is installed. */
internal const val PHONE_COMPANION_CAPABILITY = "anki_wear_phone"

/**
 * Whether the connected phone has the companion app. Null when no phone is connected: the watch
 * cannot see what is installed on a phone that is out of range, and must not claim it is missing.
 */
internal suspend fun phoneCompanionInstalled(context: Context): Boolean? =
  try {
    if (Wearable.getNodeClient(context).connectedNodes.await().isEmpty()) {
      null
    } else {
      Wearable.getCapabilityClient(context)
        .getCapability(PHONE_COMPANION_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        .await()
        .nodes
        .isNotEmpty()
    }
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (e: Exception) {
    // Wearable is a platform boundary; any failure means "unknown", never a false missing prompt.
    null
  }

/**
 * Open this app's Play Store page on the phone. Returns false when the phone is not an Android
 * device, since the iOS route has no Play Store.
 */
internal fun openOnPhone(context: Context): Boolean {
  if (PhoneTypeHelper.getPhoneDeviceType(context) != PhoneTypeHelper.DEVICE_TYPE_ANDROID) {
    return false
  }
  val intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
      .addCategory(Intent.CATEGORY_BROWSABLE)
  RemoteActivityHelper(context, ContextCompat.getMainExecutor(context)).startRemoteActivity(intent)
  return true
}
