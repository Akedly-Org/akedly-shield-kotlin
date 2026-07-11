package com.akedly.shield

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class PasskeyTest {

    @Test
    fun testBuildUrl() {
        val url = AkedlyPasskey.buildUrl("pk1.abc", "myapp")
        assertTrue(url.startsWith("https://auth.akedly.io/pk?"))
        assertTrue(url.contains("token=pk1.abc"))
        // returnUrl is percent-encoded
        assertTrue(url.contains("returnUrl=myapp%3A%2F%2Fakedly-passkey"))
    }

    @Test
    fun testBuildUrlTrimsTrailingSlash() {
        val url = AkedlyPasskey.buildUrl("t", "demo", "http://localhost:5174/")
        assertTrue(url.contains("//localhost:5174/pk?"))
    }

    @Test
    fun testParseVerified() {
        val r = AkedlyPasskey.parseResultFromQuery(
            "type=AKEDLY_PASSKEY_RESULT&purpose=auth&verified=true&transactionId=tx_9&resultToken=pkrt1.aaa.bbb"
        )
        assertTrue(r.verified)
        assertEquals("auth", r.purpose)
        assertEquals("tx_9", r.transactionId)
        assertEquals("pkrt1.aaa.bbb", r.resultToken)
        assertNull(r.reason)
    }

    @Test
    fun testParseFailedCarriesCode() {
        val r = AkedlyPasskey.parseResultFromQuery("verified=false&code=ineligible")
        assertFalse(r.verified)
        assertNull(r.resultToken)
        assertEquals("ineligible", r.reason)
    }

    @Test
    fun testParseMissingVerifiedDefaultsFalse() {
        val r = AkedlyPasskey.parseResultFromQuery("")
        assertFalse(r.verified)
        assertEquals("failed", r.reason)
    }

    @Test
    fun testVerifiedWithoutResultTokenIsNotTrusted() {
        // verified=true with no proof token must not be reported as a trusted success.
        val r = AkedlyPasskey.parseResultFromQuery("verified=true")
        assertFalse(r.verified)
        assertNull(r.resultToken)
        assertEquals("no_proof", r.reason)
    }

    @Test
    fun testWhitespaceOnlyResultTokenIsNotTrusted() {
        val r = AkedlyPasskey.parseResultFromQuery("verified=true&resultToken=%20%20")
        assertFalse(r.verified)
        assertEquals("no_proof", r.reason)
    }

    @Test
    fun testMalformedQueryEncodingIsAFailedResultNotACrash() {
        // A malformed %-escape must not throw out of the public parser.
        val r = AkedlyPasskey.parseResultFromQuery("verified=%&resultToken=pkrt1.a.b")
        assertFalse(r.verified)
    }

    @Test
    fun testRepeatedParamKeepsLastOccurrence() {
        // Documents current behavior: on a duplicated key the last occurrence wins.
        val r = AkedlyPasskey.parseResultFromQuery(
            "purpose=auth&purpose=enroll&verified=true&resultToken=pkrt1.a.b"
        )
        assertTrue(r.verified)
        assertEquals("enroll", r.purpose)
    }

    @Test
    fun testValuelessKeyIsAFailedResultNotACrash() {
        val r = AkedlyPasskey.parseResultFromQuery("?verified")
        assertFalse(r.verified)
        assertEquals("failed", r.reason)
    }

    @Test
    fun testBlankParamsNormalizeToNull() {
        // The gateway emits purpose/transactionId as empty strings when unknown — surface null.
        val r = AkedlyPasskey.parseResultFromQuery(
            "verified=true&purpose=&transactionId=&resultToken=pkrt1.a.b"
        )
        assertTrue(r.verified)
        assertNull(r.purpose)
        assertNull(r.transactionId)
    }

    @Test
    fun testBlankCodeFallsBackToFailed() {
        val r = AkedlyPasskey.parseResultFromQuery("verified=false&code=")
        assertFalse(r.verified)
        assertEquals("failed", r.reason)
    }
}
