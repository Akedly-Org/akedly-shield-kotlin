package com.akedly.shield

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Result of a hosted V1.2 passkey ceremony (auth.akedly.io/pk), deep-linked back to the app.
 *
 * The relayed signal is **non-authoritative on its own**. Confirm a sign-in by sending
 * [resultToken] to YOUR backend, which verifies it **offline** by recomputing an HMAC with
 * your Akedly API key — no polling, no server-to-server callback. See the README.
 */
data class AkedlyPasskeyResult(
    /** True only on a completed, server-verified ceremony. */
    val verified: Boolean,
    /** "auth" | "enroll" (null if the page didn't report it). */
    val purpose: String?,
    /** The passkey transaction/request id. */
    val transactionId: String?,
    /** Signed, offline-verifiable proof of a verified outcome. null on a non-verified outcome. */
    val resultToken: String?,
    /** null when verified; else "closed" | "ineligible" | "no_proof" | "failed" | <server code>. */
    val reason: String?
)

/**
 * Thrown by [AkedlyPasskey.launch] when the ceremony URL cannot be opened — e.g. no browser or
 * `ACTION_VIEW` handler is installed on the device (the wrapped [ActivityNotFoundException]).
 * Catch it to fall back to OTP. Mirrors `AkedlyTurnstileException`.
 */
class AkedlyPasskeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Hosted V1.2 passkey ceremony for Android.
 *
 * The ceremony runs in the system browser (a Custom Tab / the default browser) on the
 * akedly.io origin — so platform passkeys (fingerprint / face / device PIN via Credential
 * Manager) work — and returns via a deep link to your app's custom scheme. You register a
 * tiny redirect `Activity` (an `<intent-filter>` on the scheme) that hands the `Uri` to
 * [parseResult]. No WebView, no Digital Asset Links setup. See the README for the Activity +
 * manifest snippet, and the SDK-free flow.
 */
object AkedlyPasskey {
    const val DEFAULT_ORIGIN = "https://auth.akedly.io"

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
     * Launch the ceremony in the system browser. The result returns to your deep-link
     * Activity (which calls [parseResult]). Uses a plain VIEW intent — for a smoother
     * in-app experience launch a Custom Tab instead (see README), the URL is the same.
     *
     * **Cancellation contract.** A plain VIEW intent is fire-and-forget: it hands off to the
     * browser and cannot signal a user dismissal back to the caller. If the user abandons the
     * browser, no redirect fires and no [AkedlyPasskeyResult] is ever produced — detect that from
     * your redirect Activity's lifecycle (you resumed without having received a redirect) and
     * treat it as a cancel/OTP fallback. A ceremony the *page itself* cancels does redirect back,
     * yielding `reason = "closed"`.
     *
     * @throws AkedlyPasskeyException if no browser or `ACTION_VIEW` handler can open the ceremony
     *   URL (a device with no browser installed). Catch it and fall back to OTP — an uncaught
     *   [ActivityNotFoundException] would otherwise crash the caller.
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildUrl(token, callbackScheme, ceremonyOrigin)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw AkedlyPasskeyException(
                "No browser or ACTION_VIEW handler is available to open the passkey ceremony; " +
                    "fall back to OTP.",
                e
            )
        }
    }

    /** Parse the deep-link redirect `Uri` your Activity received into a result. */
    @JvmStatic
    fun parseResult(uri: Uri): AkedlyPasskeyResult = build(
        verifiedRaw = uri.getQueryParameter("verified") == "true",
        purpose = uri.getQueryParameter("purpose"),
        transactionId = uri.getQueryParameter("transactionId"),
        resultToken = uri.getQueryParameter("resultToken"),
        code = uri.getQueryParameter("code")
    )

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
            if (i < 0) continue
            // URLDecoder.decode throws on malformed percent-encoding (e.g. `verified=%`). This is a
            // public parser fed external redirect input, so skip a bad pair rather than crash — a
            // missing `verified` then yields verified=false (a failed result), never an exception.
            val k = try { URLDecoder.decode(pair.substring(0, i), "UTF-8") } catch (e: Exception) { continue }
            val v = try { URLDecoder.decode(pair.substring(i + 1), "UTF-8") } catch (e: Exception) { continue }
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
        return AkedlyPasskeyResult(
            verified = verified,
            purpose = purpose,
            transactionId = transactionId,
            resultToken = if (verified) resultToken else null,
            reason = if (verified) null else if (verifiedRaw) "no_proof" else (code ?: "failed")
        )
    }
}
