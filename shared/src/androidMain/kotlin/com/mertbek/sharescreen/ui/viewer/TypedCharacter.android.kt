package com.mertbek.sharescreen.ui.viewer

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

internal actual val KeyEvent.isTypedCharacter: Boolean get() = type == KeyEventType.KeyDown
