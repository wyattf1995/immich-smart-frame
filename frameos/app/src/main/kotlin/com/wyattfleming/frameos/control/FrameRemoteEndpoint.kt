package com.wyattfleming.frameos.control

import java.net.URI

data class FrameRemoteEndpoint private constructor(val pollUrl: String) {
    companion object {
        fun from(value: String): FrameRemoteEndpoint? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null)
            val normalized = URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/device/poll", null, null).toASCIIString()
            FrameRemoteEndpoint(normalized)
        }.getOrNull()
    }
}
