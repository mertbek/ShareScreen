package com.mertbek.sharescreen.ui.viewer

import androidx.compose.ui.input.key.KeyEvent

/**
 * Whether the event brings the character a key typed, in its utf16CodePoint. Android and the web bring it with
 * the key press, the desktop in an event of its own that follows, once dead keys and AltGr have had their say.
 */
internal expect val KeyEvent.isTypedCharacter: Boolean
