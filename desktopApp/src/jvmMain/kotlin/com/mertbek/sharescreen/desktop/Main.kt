package com.mertbek.sharescreen.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mertbek.sharescreen.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ShareScreen") {
        App()
    }
}
