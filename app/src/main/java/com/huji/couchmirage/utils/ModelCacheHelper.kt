package com.huji.couchmirage.utils

import java.security.MessageDigest

object ModelCacheHelper {

    fun buildCacheFileName(url: String): String {
        val extension = when {
            url.contains(".gltf", ignoreCase = true) -> ".gltf"
            url.contains(".glb", ignoreCase = true) -> ".glb"
            else -> ".glb"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(url.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return "model_$hash$extension"
    }
}
