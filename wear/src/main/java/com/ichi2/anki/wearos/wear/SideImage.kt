package com.ichi2.anki.wearos.wear

import androidx.compose.ui.graphics.ImageBitmap

/** A card side's phone-rendered image, as far as it has loaded. */
internal sealed interface SideImage {
  data object Loading : SideImage

  data object None : SideImage

  data class Ready(val bitmap: ImageBitmap) : SideImage
}
