package com.akedly.shield

import java.security.MessageDigest
import kotlinx.coroutines.yield

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun solvePowSync(challenge: String, difficulty: Int): Int {
    val prefix = "0".repeat(difficulty)
    var nonce = 0
    while (true) {
        val hash = sha256Hex("$challenge:$nonce")
        if (hash.startsWith(prefix)) {
            return nonce
        }
        nonce++
    }
}

suspend fun solvePow(challenge: String, difficulty: Int): Int {
    val prefix = "0".repeat(difficulty)
    var nonce = 0
    while (true) {
        val hash = sha256Hex("$challenge:$nonce")
        if (hash.startsWith(prefix)) {
            return nonce
        }
        nonce++
        if (nonce % 10000 == 0) {
            yield()
        }
    }
}
