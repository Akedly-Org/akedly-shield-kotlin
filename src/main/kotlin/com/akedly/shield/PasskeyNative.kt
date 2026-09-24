package com.akedly.shield

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlin.coroutines.cancellation.CancellationException

/**
 * Describes a failure from an Android Credential Manager passkey ceremony.
 *
 * @property reason the stable failure category an app can use to choose its fallback flow
 * @property domError the WebAuthn DOM error name when [reason] is [Reason.FAILED] because the
 *   credential provider rejected the ceremony
 */
class AkedlyPasskeyNativeException @JvmOverloads constructor(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
    val domError: String? = null
) : Exception(message, cause) {
    /** Stable categories for native passkey failures. */
    enum class Reason {
        /** The device or credential provider cannot run native passkeys. */
        UNSUPPORTED,

        /** The user dismissed the Credential Manager flow. */
        CANCELLED,

        /** No passkey was available for this request. */
        NO_CREDENTIAL,

        /** Credential Manager rejected the supplied WebAuthn options JSON. */
        INVALID_OPTIONS,

        /** The provider or ceremony failed for another reason. */
        FAILED
    }
}

internal class AkedlyPasskeyNativeCore(
    private val sdkInt: () -> Int,
    private val isSupported: () -> Boolean
) {
    suspend fun run(
        optionsJson: String,
        platformCall: suspend (String) -> String
    ): String {
        if (sdkInt() < Build.VERSION_CODES.P || !isSupported()) {
            throw AkedlyPasskeyNativeException(
                reason = AkedlyPasskeyNativeException.Reason.UNSUPPORTED,
                message = "Native passkeys require Android 9 or a supported credential provider."
            )
        }
        return try {
            platformCall(optionsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw mapCredentialFailure(error)
        }
    }
}

internal class AkedlyCredentialManagerAdapter(
    private val credentialManagerFactory: (Context) -> CredentialManager = { context ->
        CredentialManager.create(context)
    }
) {
    suspend fun register(activity: Activity, optionsJson: String): String {
        val request = CreatePublicKeyCredentialRequest(optionsJson)
        val response = credentialManagerFactory(activity).createCredential(activity, request)
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw IllegalStateException(
                "Credential Manager returned a non-passkey registration response."
            )
    }

    suspend fun authenticate(activity: Activity, optionsJson: String): String {
        val option = GetPublicKeyCredentialOption(optionsJson)
        val request = GetCredentialRequest(listOf(option))
        val response = credentialManagerFactory(activity).getCredential(activity, request)
        return (response.credential as? PublicKeyCredential)
            ?.authenticationResponseJson
            ?: throw IllegalStateException(
                "Credential Manager returned a non-passkey authentication response."
            )
    }
}

internal fun nativeIsSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    return runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    }.getOrDefault(false)
}

private val credentialManagerAdapter = AkedlyCredentialManagerAdapter()

internal suspend fun nativeRegister(activity: Activity, optionsJson: String): String =
    nativeCore(activity).run(optionsJson) { json ->
        credentialManagerAdapter.register(activity, json)
    }

internal suspend fun nativeAuthenticate(activity: Activity, optionsJson: String): String =
    nativeCore(activity).run(optionsJson) { json ->
        credentialManagerAdapter.authenticate(activity, json)
    }

private fun nativeCore(activity: Activity) =
    AkedlyPasskeyNativeCore(
        sdkInt = { Build.VERSION.SDK_INT },
        isSupported = { nativeIsSupported(activity) }
    )

internal fun mapCredentialFailure(error: Throwable): AkedlyPasskeyNativeException {
    if (error is CancellationException) throw error
    if (error is AkedlyPasskeyNativeException) return error
    val reason = when (error) {
        is CreateCredentialCancellationException,
        is GetCredentialCancellationException -> AkedlyPasskeyNativeException.Reason.CANCELLED

        is NoCredentialException -> AkedlyPasskeyNativeException.Reason.NO_CREDENTIAL

        is CreateCredentialProviderConfigurationException,
        is CreateCredentialUnsupportedException,
        is CreateCredentialNoCreateOptionException,
        is GetCredentialProviderConfigurationException,
        is GetCredentialUnsupportedException -> AkedlyPasskeyNativeException.Reason.UNSUPPORTED

        is IllegalArgumentException -> AkedlyPasskeyNativeException.Reason.INVALID_OPTIONS
        else -> AkedlyPasskeyNativeException.Reason.FAILED
    }
    val domError = when (error) {
        is CreatePublicKeyCredentialDomException -> error.domError.type
        is GetPublicKeyCredentialDomException -> error.domError.type
        else -> null
    }
    return AkedlyPasskeyNativeException(
        reason = reason,
        message = error.message ?: error::class.java.simpleName,
        cause = error,
        domError = domError
    )
}
