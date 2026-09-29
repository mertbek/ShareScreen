package com.mertbek.sharescreen.signaling

import kotlinx.serialization.Serializable

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)
