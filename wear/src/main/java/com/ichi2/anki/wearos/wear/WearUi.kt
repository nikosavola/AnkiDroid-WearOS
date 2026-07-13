package com.ichi2.anki.wearos.wear

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.lazy.ResponsiveTransformationSpec
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.TransformationVariableSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec

// Shared Wear UI helpers used by every scrollable screen. Top-level (no Activity state) so screens
// extracted out of MainActivity can reuse them.

/**
 * Route rotary bezel/crown input into [state] with animated scrolling and haptic detents (the
 * built-in Wear behavior). Grabs focus when composed; only one screen shows at a time so they
 * don't fight over focus.
 */
@Composable
fun Modifier.rotaryScroll(state: ScrollableState): Modifier {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }
  return this.rotaryScrollable(
    RotaryScrollableDefaults.behavior(scrollableState = state),
    focusRequester = focusRequester,
  )
}

/**
 * The M3 Expressive edge-morph spec for our lists. Same as the default TransformingLazyColumn
 * transform but with a higher container/content alpha floor at the bezel (0.75 instead of the
 * default 0.5), so cards, switches and buttons stay legible near the top/bottom edges rather than
 * fading half-out. targetValue stays 1.0 (fully opaque at the vertical center).
 */
@Composable
fun rememberEdgeMorphSpec(): TransformationSpec {
  val edgeAlpha = TransformationVariableSpec(0.75f)
  return rememberTransformationSpec(
    ResponsiveTransformationSpec.smallScreen(containerAlpha = edgeAlpha, contentAlpha = edgeAlpha),
    ResponsiveTransformationSpec.largeScreen(containerAlpha = edgeAlpha, contentAlpha = edgeAlpha),
  )
}
