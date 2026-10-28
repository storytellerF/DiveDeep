package com.storyteller_f.divedeep.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private var primaryTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fixture)
        // A unique source prevents the real-model integration test from passing on an old cache entry.
        intent.getStringExtra("test_title")?.let { title ->
            findViewById<TextView>(R.id.title_text).text = title
        }
        val primaryButton = findViewById<Button>(R.id.primary_button)
        primaryButton.setOnClickListener {
            primaryTapCount += 1
            primaryButton.text = "已点击 $primaryTapCount 次"
        }
    }
}
