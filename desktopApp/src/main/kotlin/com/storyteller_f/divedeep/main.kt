package com.storyteller_f.divedeep

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "DiveDeep",
    ) {
        App()
    }
}