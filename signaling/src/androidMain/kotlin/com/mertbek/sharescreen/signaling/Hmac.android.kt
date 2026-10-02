package com.mertbek.sharescreen.signaling

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
