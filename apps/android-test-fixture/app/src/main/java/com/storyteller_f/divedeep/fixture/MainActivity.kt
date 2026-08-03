package com.storyteller_f.divedeep.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button

class MainActivity : Activity() {
    private var primaryTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fixture)
        val primaryButton = findViewById<Button>(R.id.primary_button)
        primaryButton.setOnClickListener {
            primaryTapCount += 1
            primaryButton.text = "已点击 $primaryTapCount 次"
        }
    }
}
