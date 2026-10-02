package com.mertbek.sharescreen.signaling

/** HMAC-SHA256 of [data] with [key]. Only the apps need it: the browser does not share on the local network. */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
