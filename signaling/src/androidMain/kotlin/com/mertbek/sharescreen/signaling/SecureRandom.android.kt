package com.mertbek.sharescreen.signaling

import java.security.SecureRandom

private val random = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
