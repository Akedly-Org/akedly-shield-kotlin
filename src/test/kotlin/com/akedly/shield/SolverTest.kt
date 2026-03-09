package com.akedly.shield

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SolverTest {

    private fun verifyNonce(challenge: String, nonce: Int, difficulty: Int): Boolean {
        val input = "$challenge:$nonce"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val prefix = "0".repeat(difficulty)
        return hex.startsWith(prefix)
    }

    @Test
    fun testSolvePowSyncDifficulty3() {
        val challenge = "a".repeat(64)
        val nonce = solvePowSync(challenge, 3)
        assertTrue(verifyNonce(challenge, nonce, 3))
    }

    @Test
    fun testSolvePowSyncDifficulty4() {
        val challenge = "a".repeat(64)
        val nonce = solvePowSync(challenge, 4)
        assertTrue(verifyNonce(challenge, nonce, 4))
    }

    @Test
    fun testConsistentResults() {
        val challenge = "a".repeat(64)
        val nonce1 = solvePowSync(challenge, 3)
        val nonce2 = solvePowSync(challenge, 3)
        assertEquals(nonce1, nonce2, "Same challenge + difficulty should produce same nonce")
    }

    @Test
    fun testDifficulty1EdgeCase() {
        val challenge = "b".repeat(64)
        val nonce = solvePowSync(challenge, 1)
        assertTrue(verifyNonce(challenge, nonce, 1))
    }
}
