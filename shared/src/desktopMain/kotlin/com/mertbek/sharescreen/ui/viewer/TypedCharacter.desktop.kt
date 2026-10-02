package com.mertbek.sharescreen.ui.viewer

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent

internal actual val KeyEvent.isTypedCharacter: Boolean get() = awtEventOrNull?.id == java.awt.event.KeyEvent.KEY_TYPED
