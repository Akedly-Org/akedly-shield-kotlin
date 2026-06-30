package com.akedly.shield

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
    /** null when verified; else "closed" | "ineligible" | <server code>. */
    val reason: String?
)

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
     */
    @JvmStatic
    @JvmOverloads
    fun launch(
        context: Context,
        token: String,
        callbackScheme: String,
        ceremonyOrigin: String = DEFAULT_ORIGIN
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildUrl(token, callbackScheme, ceremonyOrigin)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
