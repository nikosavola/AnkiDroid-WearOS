package fi.nikosavola.ankidroid.wearos.phone

/** Framework-bound actions the Activity performs on behalf of the screen. */
class PhoneActions(
  val onSync: () -> Unit = {},
  val onRequestCardAccess: () -> Unit = {},
  val onOpenAppSettings: () -> Unit = {},
  val onRefreshDecks: () -> Unit = {},
  val onCopyReport: (String) -> Unit = {},
)
