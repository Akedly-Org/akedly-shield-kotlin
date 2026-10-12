package com.akedly.shield

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PasskeyNativeTest {

    @Test
    fun getNotAllowedErrorMapsToCancelledWithDomError() {
        val mapped = mapCredentialFailure(GetPublicKeyCredentialDomException(NotAllowedError()))

        assertEquals(AkedlyPasskeyNativeException.Reason.CANCELLED, mapped.reason)
        assertEquals("NotAllowedError", mapped.domError)
    }

    @Test
    fun createNotAllowedErrorMapsToCancelledWithDomError() {
        val mapped = mapCredentialFailure(CreatePublicKeyCredentialDomException(NotAllowedError()))

        assertEquals(AkedlyPasskeyNativeException.Reason.CANCELLED, mapped.reason)
        assertEquals("NotAllowedError", mapped.domError)
    }

    @Test
    fun registrationOptionsJsonIsPassedToPlatformUnchanged() {
        val optionsJson = " \n{\"challenge\":\"réservé\",\"extensions\":{\"custom\":true}}\t "
        var receivedOptions: String? = null
        val core = AkedlyPasskeyNativeCore(sdkInt = { 28 }, isSupported = { true })

        runBlocking {
            core.run(optionsJson) { received ->
                receivedOptions = received
                "{}"
            }
        }

        assertSame(optionsJson, receivedOptions)
    }

    @Test
    fun authenticationResponseJsonIsReturnedUnchanged() {
        val responseJson = "{\"id\":\"réponse\",\"response\":{}} \n"
        val core = AkedlyPasskeyNativeCore(sdkInt = { 28 }, isSupported = { true })

        val returnedResponse = runBlocking {
            core.run("{\"challenge\":\"auth\"}") { responseJson }
        }

        assertSame(responseJson, returnedResponse)
    }

    @Test
    fun credentialManagerCancellationMapsToCancelled() {
        val core = AkedlyPasskeyNativeCore(sdkInt = { 28 }, isSupported = { true })

        val mapped = assertFailsWith<AkedlyPasskeyNativeException> {
            runBlocking {
                core.run("{}") {
                    throw GetCredentialCancellationException("user cancelled")
                }
            }
        }

        assertEquals(AkedlyPasskeyNativeException.Reason.CANCELLED, mapped.reason)
    }

    @Test
    fun noCredentialMapsToNoCredential() {
        val core = AkedlyPasskeyNativeCore(sdkInt = { 28 }, isSupported = { true })

        val mapped = assertFailsWith<AkedlyPasskeyNativeException> {
            runBlocking {
                core.run("{}") {
                    throw NoCredentialException("no passkey")
                }
            }
        }

        assertEquals(AkedlyPasskeyNativeException.Reason.NO_CREDENTIAL, mapped.reason)
    }
}
