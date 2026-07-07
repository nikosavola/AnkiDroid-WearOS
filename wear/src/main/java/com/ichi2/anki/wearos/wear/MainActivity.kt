package com.ichi2.anki.wearos.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import uniffi.wearos_core.greetWatch
import uniffi.wearos_core.getSampleCard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

@Composable
fun WearApp() {
    MaterialTheme {
        val listState = rememberScalingLazyListState()
        Scaffold(
            positionIndicator = {
                PositionIndicator(scalingLazyListState = listState)
            }
        ) {
            val greeting = greetWatch("Wear OS Watch")
            val card = getSampleCard()

            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                item {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.title3,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                item {
                    Text(
                        text = "Sample Card from Rust Core:",
                        style = MaterialTheme.typography.caption1
                    )
                }
                item {
                    Text(
                        text = "Q: ${card.front}",
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                item {
                    Text(
                        text = "A: ${card.back}",
                        style = MaterialTheme.typography.body2
                    )
                }
            }
        }
    }
}
