package dev.mimir.fakeemulator

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras?.keySet()
            ?.joinToString("\n") { "  $it = ${intent.extras?.get(it)}" } ?: "  (none)"
        val report = """
            FAKE EMULATOR — intent received
            action: ${intent.action}
            data:   ${intent.data}
            flags:  0x${Integer.toHexString(intent.flags)}
            extras:
            $extras
        """.trimIndent()
        setContentView(ScrollView(this).apply {
            addView(TextView(context).apply { text = report; textSize = 16f; setPadding(32, 64, 32, 32) })
        })
    }
}
