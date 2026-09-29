package com.mertbek.sharescreen.signaling

private fun fillRandom(size: Int): JsAny = js("crypto.getRandomValues(new Uint8Array(size))")

private fun byteAt(array: JsAny, index: Int): Byte = js("array[index]")

actual fun secureRandomBytes(size: Int): ByteArray {
    val values = fillRandom(size)
    return ByteArray(size) { byteAt(values, it) }
}
