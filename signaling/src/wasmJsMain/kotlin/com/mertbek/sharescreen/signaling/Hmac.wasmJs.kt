package com.mertbek.sharescreen.signaling

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    throw UnsupportedOperationException("Remembered devices are only used on the local network")
