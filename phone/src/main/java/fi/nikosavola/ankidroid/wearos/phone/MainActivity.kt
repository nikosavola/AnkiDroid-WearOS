package fi.nikosavola.ankidroid.wearos.phone

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import uniffi.wearos_core.greetWatch
import uniffi.wearos_core.getSampleCard

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this)
        
        val greeting = greetWatch("Phone Companion")
        val card = getSampleCard()
        
        textView.text = "$greeting\n\nSample Card from Rust Core:\nID: ${card.id}\nFront: ${card.front}\nBack: ${card.back}"
        textView.textSize = 18f
        textView.setPadding(32, 32, 32, 32)
        setContentView(textView)
    }
}
