package com.ichi2.anki.wearos.phone

import android.app.Application
import android.os.StrictMode
import com.google.android.material.color.DynamicColors

/**
 * Applies Material You dynamic color to the imperative Material Views (the settings, connection-info
 * and sync-help dialogs), so they match the Compose UI, which already themes via
 * dynamicDark/LightColorScheme. No-op below API 31. The Compose surfaces theme themselves and are
 * unaffected.
 */
class AnkiPhoneApp : Application() {
  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
      StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
    DynamicColors.applyToActivitiesIfAvailable(this)
  }
}
