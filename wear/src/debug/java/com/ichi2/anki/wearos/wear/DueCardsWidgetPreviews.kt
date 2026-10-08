package com.ichi2.anki.wearos.wear

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.RectangularAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.RoundAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.SquircleAllWidgetPreviewParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview

// Debug-only: wear-tooling-preview drags ui-tooling into the APK.

private val previewSnapshot =
  DueWidgetSnapshot(
    hasDecks = true,
    dueNow = 23,
    learningLater = 4,
    nextLearningDueMs = 0L,
    topDecks =
      listOf(WidgetDeck(1, "Kanji", 12), WidgetDeck(2, "Spanish", 8), WidgetDeck(3, "Biology", 3)),
  )

private class PreviewDueCardsWidget(private val snapshot: DueWidgetSnapshot) : GlanceWearWidget() {
  override suspend fun provideWidgetData(
    context: Context,
    params: WearWidgetParams,
  ): WearWidgetData = dueCardsDocument(params, snapshot) { "14:35" }
}

@Preview(name = "Squircle", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
private fun DueCardsSquirclePreview(
  @PreviewParameter(SquircleAllWidgetPreviewParams::class) params: WearWidgetParams
) = WearWidgetPreview(PreviewDueCardsWidget(previewSnapshot), params)

@Preview(name = "Round", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
private fun DueCardsRoundPreview(
  @PreviewParameter(RoundAllWidgetPreviewParams::class) params: WearWidgetParams
) = WearWidgetPreview(PreviewDueCardsWidget(previewSnapshot), params)

@Preview(name = "Caught up", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
private fun DueCardsCaughtUpPreview(
  @PreviewParameter(RoundAllWidgetPreviewParams::class) params: WearWidgetParams
) =
  WearWidgetPreview(
    PreviewDueCardsWidget(DueWidgetSnapshot(true, 0, 2, 0L, emptyList())),
    params,
  )

// Source for the picker's previewImage drawables (uncropped, so each watch applies its own mask).
@Preview(name = "Picker asset", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
private fun DueCardsPickerPreview(
  @PreviewParameter(RectangularAllWidgetPreviewParams::class) params: WearWidgetParams
) = WearWidgetPreview(PreviewDueCardsWidget(previewSnapshot), params)
