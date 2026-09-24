package com.akedly.shield

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Result of a hosted V1.2 passkey ceremony (auth.akedly.io/pk), deep-linked back to the app.
 *
 * The relayed signal is **non-authoritative on its own**. Confirm a sign-in by sending
 * [resultToken] to YOUR backend, which verifies it **offline** by recomputing an HMAC with
 * your Akedly API key — no polling, no server-to-server callback to Akedly. See the README.
 */
data class AkedlyPasskeyResult(
    /** True only when the relay claims completion and includes a nonblank result token.
     * The app must still send that token to its backend for HMAC and transaction verification. */
    val verified: Boolean,
    /** "auth" | "enroll" (null when the page reports it blank or not at all). */
    val purpose: String?,
    /** The passkey transaction/request id (null when blank or absent). */
    val transactionId: String?,
    /** Signed, offline-verifiable proof of a verified outcome. null on a non-verified outcome. */
    val resultToken: String?,
    /**
     * null when verified; else "no_proof" | "failed". A server `code` param is reserved in the
     * redirect contract but the /pk page currently never sends one.
     */
    val reason: String?
)

/**
 * Thrown by [AkedlyPasskey.launch] when the ceremony URL cannot be opened — e.g. no browser is
 * installed on the device (the wrapped [ActivityNotFoundException]).
 * Catch it to fall back to OTP. Mirrors `AkedlyTurnstileException`.
 */
class AkedlyPasskeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Hosted and native V1.2 passkey ceremonies for Android.
 *
 * [launch] opens the hosted ceremony in a Chrome Custom Tab on the akedly.io origin and returns
 * through a deep link parsed by [parseResult]. The hosted flow does not use a WebView or require
 * Digital Asset Links. See the README for the Activity and manifest snippet.
 *
 * The native [register] and [authenticate] members use Android Credential Manager directly on
 * Android 9+ when [isNativeSupported] is true. They require an approved Android App Registration
 * and Digital Asset Links instead of the hosted redirect.
 */
object AkedlyPasskey {
    const val DEFAULT_ORIGIN = "https://auth.akedly.io"

    /**
     * Register a passkey through Android Credential Manager.
     *
     * Pass [optionsJson] unchanged from the `data.options` object returned by your backend's
     * native `/register-options` request. The returned JSON is the WebAuthn registration response
     * to post to your backend's `/register-verify` endpoint. Use [isNativeSupported] first and
     * fall back to [launch] when native support is unavailable.
     *
     * This is a Kotlin-coroutines-only API; Java callers should use the hosted [launch] API.
     *
     * @param activity the foreground Activity that owns the Credential Manager UI
     * @param optionsJson the serialized WebAuthn creation options from your backend
     * @throws AkedlyPasskeyNativeException when the provider cannot complete the ceremony
     */
    @JvmStatic
    suspend fun register(activity: Activity, optionsJson: String): String =
        nativeRegister(activity, optionsJson)

    /**
     * Authenticate with a passkey through Android Credential Manager.
     *
     * Pass [optionsJson] unchanged from the `data.options` object returned by your backend's
     * native `/auth-options` request. The returned JSON is the WebAuthn authentication response to
     * post to your backend's `/auth-verify` endpoint. Use [isNativeSupported] first and fall back
     * to [launch] when native support is unavailable.
     *
     * This is a Kotlin-coroutines-only API; Java callers should use the hosted [launch] API.
     *
     * @param activity the foreground Activity that owns the Credential Manager UI
     * @param optionsJson the serialized WebAuthn request options from your backend
     * @throws AkedlyPasskeyNativeException when the provider cannot complete the ceremony
     */
    @JvmStatic
    suspend fun authenticate(activity: Activity, optionsJson: String): String =
        nativeAuthenticate(activity, optionsJson)

    /**
     * Return whether this device can attempt a native passkey ceremony.
     *
     * Native ceremonies require Android 9 (API 28) or newer. Android 9 through Android 13 also
     * require an available Google Play Services credential provider; Android 14 and newer can use
     * the platform Credential Manager without Play Services. This check does not verify that an
     * app's package and signing fingerprint have been approved by Akedly.
     */
    @JvmStatic
    fun isNativeSupported(context: Context): Boolean = nativeIsSupported(context)

    /**
     * Build the ceremony URL: `<origin>/pk?token=…&returnUrl=<scheme>://akedly-passkey`.
     * Pure (no Android deps) so it is unit-testable on the JVM.
     */
    @JvmStatic
    @JvmOverloads
    fun buildUrl(
        token: String,
        callbackScheme: String,
        ceremonyOrigin: String = DEFAULT_ORIGIN
    ): String {
        val origin = ceremonyOrigin.trimEnd('/')
        val tok = URLEncoder.encode(token, "UTF-8")
        val rt = URLEncoder.encode("$callbackScheme://akedly-passkey", "UTF-8")
        return "$origin/pk?token=$tok&returnUrl=$rt"
    }

    /**
     * Launch the ceremony in a browser-backed Custom Tab. The result returns to your deep-link
     * Activity (which calls [parseResult]).
     *
     * **Cancellation contract.** This launch is fire-and-forget: the SDK binds no
     * `CustomTabsSession`, so a user dismissal is never signalled back to the caller. If the user
     * abandons the browser, no redirect fires and no [AkedlyPasskeyResult] is ever produced — the
     * /pk page has no cancel redirect of its own, so abandonment in any form produces no redirect
     * at all.
     *
     * **Detect it on the CALLING Activity, not on your redirect Activity.** Your redirect Activity
     * only ever exists *because* a redirect arrived — on abandonment it is never instantiated, so
     * watching its lifecycle detects nothing and the user waits forever with no OTP fallback. The
     * correct signal is your calling Activity resuming without [parseResult] having delivered a
     * result: set a "ceremony in flight" flag before [launch], clear it in [parseResult], and on
     * the calling Activity's `onResume` treat a still-set flag as a cancel.
     *
     * @param context prefer an `Activity`. With one, the Custom Tab opens inside your task, which
     *   is the intended experience; any other context must be launched into a separate task
     *   (`FLAG_ACTIVITY_NEW_TASK`), which behaves like the old browser kick-out.
     * @throws AkedlyPasskeyException if no browser can open the Custom Tab ceremony URL. Catch it
     *   and fall back to OTP — an uncaught [ActivityNotFoundException] would otherwise crash the
     *   caller.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(AkedlyPasskeyException::class)
    fun launch(
        context: Context,
        token: String,
        callbackScheme: String,
        ceremonyOrigin: String = DEFAULT_ORIGIN
    ) {
        val customTab = CustomTabsIntent.Builder().build()
        if (context !is Activity) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTab.launchUrl(context, Uri.parse(buildUrl(token, callbackScheme, ceremonyOrigin)))
        } catch (e: ActivityNotFoundException) {
            throw AkedlyPasskeyException(
                "No browser is available to open the passkey ceremony in a Custom Tab; " +
                    "fall back to OTP.",
                e
            )
        }
    }

    /** Parse the deep-link redirect `Uri` your Activity received into a result. */
    @JvmStatic
    fun parseResult(uri: Uri): AkedlyPasskeyResult {
        val encodedQuery = try {
            uri.encodedQuery
        } catch (e: Exception) {
            null
        }
        return encodedQuery?.let(::parseResultFromQuery) ?: failedResult()
    }

    /**
     * Parse a result from a raw `key=value&…` query string. Pure (no Android deps) so it is
     * unit-testable on the JVM; [parseResult] is the Android `Uri` convenience over it.
     */
    @JvmStatic
    fun parseResultFromQuery(query: String): AkedlyPasskeyResult {
        val params = HashMap<String, String>()
        for (pair in query.removePrefix("?").split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            // URLDecoder.decode throws on malformed percent-encoding (e.g. `verified=%`). This is a
            // public parser fed external redirect input, so fail the entire result rather than
            // accept a partial callback or crash.
            val rawKey = if (i < 0) pair else pair.substring(0, i)
            val k = decode(rawKey) ?: return failedResult()
            if (k in RESERVED_RESULT_PARAMS && params.containsKey(k)) {
                return failedResult()
            }
            val rawValue = if (i < 0) "" else pair.substring(i + 1)
            val v = decode(rawValue) ?: return failedResult()
            params[k] = v
        }
        return build(
            verifiedRaw = params["verified"] == "true",
            purpose = params["purpose"],
            transactionId = params["transactionId"],
            resultToken = params["resultToken"],
            code = params["code"]
        )
    }

    /**
     * Build a result, enforcing the contract that a `verified` outcome MUST carry the
     * offline-verifiable [AkedlyPasskeyResult.resultToken]. A bare `…?verified=true` with no
     * token (a malformed or externally-triggered intent) is reported as `verified = false`,
     * `reason = "no_proof"` — never as a trusted success — so the README's
     * `result.resultToken!!` can never hit a null.
     */
    private fun build(
        verifiedRaw: Boolean,
        purpose: String?,
        transactionId: String?,
        resultToken: String?,
        code: String?
    ): AkedlyPasskeyResult {
        val verified = verifiedRaw && !resultToken.isNullOrBlank()
        val normalizedCode = code?.takeIf { it.isNotBlank() }
        return AkedlyPasskeyResult(
            verified = verified,
            purpose = purpose?.takeIf { it.isNotBlank() },
            transactionId = transactionId?.takeIf { it.isNotBlank() },
            resultToken = if (verified) resultToken else null,
            reason = if (verified) null else if (verifiedRaw) "no_proof" else (normalizedCode ?: "failed")
        )
    }

    private fun failedResult() =
        build(
            verifiedRaw = false,
            purpose = null,
            transactionId = null,
            resultToken = null,
            code = null
        )

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    private val RESERVED_RESULT_PARAMS =
        setOf("type", "purpose", "verified", "transactionId", "code", "resultToken")
}
