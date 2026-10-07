package fi.nikosavola.ankidroid.wearos.wear

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.confirmationDialogCurvedText

/**
 * Transient Wear M3 failure feedback for an unreachable phone, replacing the old toasts. [message]
 * drives visibility: it auto-dismisses after the dialog's own timeout and swallows a swipe, both of
 * which clear it through [onDismissRequest].
 */
@Composable
internal fun PhoneFailureDialog(message: String?, onDismissRequest: () -> Unit) {
  if (message == null) return
  // curvedTextStyle is a composable getter, so it can't be read from inside the plain slot lambda.
  val curvedStyle = ConfirmationDialogDefaults.curvedTextStyle
  FailureConfirmationDialog(
    visible = true,
    onDismissRequest = onDismissRequest,
    curvedText = { confirmationDialogCurvedText(message, curvedStyle) },
  )
}
