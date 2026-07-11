# AkedlyShield (Kotlin)

Client-side PoW solver, Turnstile helper, and passkey launcher for Akedly Shield V1.2 (Android). Published as an Android library (AAR): the Turnstile helper needs a `WebView` and the passkey launcher needs `Intent`/`Uri`, so the artifact targets Android, not a plain JVM. The PoW solver itself is pure Kotlin — copy it into a JVM/server project if you need server-side solving.

## Installation

### Gradle

```kotlin
dependencies {
    implementation("com.akedly:shield:1.0.0")
}
```

## Quick Start

```kotlin
import com.akedly.shield.solvePow
import com.akedly.shield.solvePowSync

// Coroutine-based (recommended for Android)
val nonce = solvePow(challenge, difficulty)

// Blocking (for Java interop or background threads)
val nonce = solvePowSync(challenge, difficulty)
```

## API

### `suspend fun solvePow(challenge: String, difficulty: Int): Int`

Coroutine-based solver that yields every 10,000 iterations. Use within a coroutine scope.

### `fun solvePowSync(challenge: String, difficulty: Int): Int`

Blocking solver. Use on background threads or for Java interop.

### `AkedlyTurnstile` (Android)

Creates an invisible `WebView` to load the Turnstile bridge page.

```kotlin
val turnstile = AkedlyTurnstile(context)
val token = turnstile.getToken("your-site-key")
// Use token in your API request as turnstileToken
```

**Parameters:**
- `context` — Android Context
- `bridgeDomain` — bridge page domain (default: `turnstile.akedly.io`)

## Full Integration Example

```kotlin
import com.akedly.shield.solvePow
import com.akedly.shield.AkedlyTurnstile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun sendOTP(phone: String, apiKey: String, pipelineID: String, context: Context) {
    // 1. Get challenge
    val challengeUrl = "https://api.akedly.io/api/v1.2/transactions/challenge?APIKey=$apiKey&pipelineID=$pipelineID"
    val challengeData = withContext(Dispatchers.IO) {
        // Use your HTTP client (OkHttp, Retrofit, etc.) to GET challengeUrl
        // Parse JSON response
    }

    // 2. Solve PoW (if required)
    var powSolution: Map<String, Any>? = null
    if (challengeData["challengeRequired"] == true) {
        val challenge = challengeData["challenge"] as String
        val difficulty = challengeData["difficulty"] as Int
        val nonce = withContext(Dispatchers.Default) {
            solvePow(challenge, difficulty)
        }
        powSolution = mapOf(
            "challengeToken" to challengeData["challengeToken"]!!,
            "nonce" to nonce
        )
    }

    // 3. Get Turnstile token (if required)
    var turnstileToken: String? = null
    val turnstileData = challengeData["turnstile"] as? Map<*, *>
    if (turnstileData?.get("required") == true) {
        val siteKey = turnstileData["siteKey"] as String
        val turnstile = AkedlyTurnstile(context)
        turnstileToken = withContext(Dispatchers.Main) {
            turnstile.getToken(siteKey)
        }
    }

    // 4. Send OTP with powSolution and turnstileToken
}
```

## Algorithm

```
hash = SHA256(challenge + ":" + nonce.toString())   // hex digest
valid = hash.startsWith("0".repeat(difficulty))      // leading hex zeros
```

## Passkeys (V1.2)

Run a hosted V1.2 passkey ceremony on `auth.akedly.io/pk` from an Android app. The ceremony
runs in the **system browser / a Custom Tab** on the akedly.io origin — so platform passkeys
(fingerprint / face / device PIN via Credential Manager) work — and returns via a **deep link**
to your app's custom scheme. **No WebView, no Digital Asset Links.**

```kotlin
import com.akedly.shield.AkedlyPasskey
import com.akedly.shield.AkedlyPasskeyException

// 1. Your backend clears the gate + starts the ceremony:
//    POST /api/v1.2/transactions/passkey/auth-options  -> { data: { ceremonyToken } }
val ceremonyToken = myBackend.startPasskeyAuth(phone)   // or a "no passkey" code -> use OTP

// 2. Launch it. The result returns to your redirect Activity (below).
//    launch() throws AkedlyPasskeyException if the device has no browser to open the ceremony.
try {
    AkedlyPasskey.launch(context, ceremonyToken, callbackScheme = "myapp")
    // for QA: AkedlyPasskey.launch(context, token, "myapp", ceremonyOrigin = "http://localhost:5174")
} catch (e: AkedlyPasskeyException) {
    // no browser / ACTION_VIEW handler on the device -> fall back to OTP
}
```

Register a tiny redirect `Activity` for the scheme, and parse the result:

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".PasskeyRedirectActivity" android:exported="true" android:launchMode="singleTask">
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="myapp" android:host="akedly-passkey" />
  </intent-filter>
</activity>
```

```kotlin
class PasskeyRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A third party can target this exported Activity with no data — bail out, don't NPE.
        val data = intent?.data ?: run { finish(); return }
        val result = AkedlyPasskey.parseResult(data)
        if (result.verified) {
            // 3. Confirm offline on YOUR backend (no polling, no callback) — see below.
            myBackend.completeSignIn(result.resultToken!!)
        } else {
            // result.reason: "no_proof" | "failed" -> OTP fallback
        }
        finish()
    }
}
```

To **enroll** a passkey, pass the `enrollmentToken` from a successful OTP `/verify` as the
token instead — same API; enrollment is proven on the next successful sign-in.

### Custom Tab (optional)

`AkedlyPasskey.launch` fires a plain VIEW intent; for a smoother in-app feel, open the same
URL in a Custom Tab instead. Both a full browser and a Custom Tab run on the user's default
browser, so they share the same Credential Manager passkeys.

```kotlin
dependencies {
    implementation("androidx.browser:browser:1.7.0")
}
```

```kotlin
import androidx.browser.customtabs.CustomTabsIntent

// buildUrl returns a String — parse it into a Uri for the Custom Tab.
val url = Uri.parse(AkedlyPasskey.buildUrl(ceremonyToken, callbackScheme = "myapp"))
CustomTabsIntent.Builder().build().launchUrl(context, url)
```

### Verify the result (seamless — no polling)

A verified ceremony carries a **`resultToken`**: a compact, signed proof of the outcome. You
confirm a sign-in by verifying it **offline on your own backend** — no `/result` poll, no
server-to-server callback to Akedly. The token is HMAC-signed with **your account API key** (the
same secret you already use to create transactions), so only your backend — which holds that key
— can verify it.

> ⚠️ **Verify on your server, never in the app.** Your API key is a server secret. Do **not**
> embed it in the Android app or verify the token on-device. The app forwards `result.resultToken`
> to your backend; your backend verifies it and creates the session.

**Token format**

```
pkrt1.<base64url(payloadJSON)>.<base64url(signature)>
```

`payloadJSON` (a JSON object, before base64url):

```json
{ "v": 1, "purpose": "auth", "transactionId": "…", "pipelineId": "…",
  "verified": true, "iat": 1730000000000, "exp": 1730000120000 }
```

`iat`/`exp` are **milliseconds** since the Unix epoch (`exp` ≈ 2 minutes after `iat`).

**The signature — exactly what is HMAC'd, in this order**

```
signature = HMAC_SHA256( key = YOUR_API_KEY, message = "pkrt1." + base64url(payloadJSON) )
```

- **algorithm:** HMAC-SHA256.
- **key:** your account API key, as raw UTF-8 bytes.
- **message:** the ASCII string `"pkrt1."` immediately followed by the base64url payload segment
  — i.e. **the whole token with the trailing `.<signature>` removed**. The `pkrt1.` prefix **is
  part of the signed bytes**. (Equivalently: `token` up to, but not including, the final `.`.)
- **base64url is unpadded** (RFC 4648 §5: `+`→`-`, `/`→`_`, `=` stripped). Java's
  `Base64.getUrlDecoder()` accepts unpadded input as-is.

**Verification steps (do them all, in order)**

1. Reject if `token` doesn't start with `pkrt1.`.
2. Strip the `pkrt1.` prefix, then split the remainder on `.` — there must be **exactly
   two** segments, `dataSegment` and `sigSegment` (reject otherwise).
3. Compute `expected = HMAC_SHA256(apiKey, "pkrt1." + dataSegment)`.
4. **Constant-time-compare** `expected` against `base64url-decode(sigSegment)`. Reject on
   mismatch (forged / tampered).
5. `payload = JSON(base64url-decode(dataSegment))`.
6. Reject if `now_ms > payload.exp` (expired).
7. Require `payload.verified == true`.
8. Require `payload.transactionId ==` the transaction **you** started — this binds the proof to
   *this* sign-in. Only then create the session.

**Reference verifier — Node.js** (zero deps; portable to any backend language):

```javascript
import crypto from 'node:crypto';

export function verifyAkedlyResult(token, apiKey) {
  if (!apiKey) return null;                                       // fail closed on an empty key
  if (typeof token !== 'string' || !token.startsWith('pkrt1.')) return null;
  const parts = token.slice('pkrt1.'.length).split('.');
  if (parts.length !== 2) return null;                              // exactly two segments
  const [data, sig] = parts;
  const b64u = /^[A-Za-z0-9_-]+$/;                                  // strict unpadded base64url
  if (!b64u.test(data) || !b64u.test(sig)) return null;            // no alternate serializations
  // string-keyed single-use checks must see one canonical serialization
  if (Buffer.from(data, 'base64url').toString('base64url') !== data ||
      Buffer.from(sig, 'base64url').toString('base64url') !== sig) return null;
  const expected = crypto.createHmac('sha256', apiKey).update('pkrt1.' + data).digest();
  const given = Buffer.from(sig, 'base64url');
  if (expected.length !== given.length || !crypto.timingSafeEqual(expected, given)) return null;
  let payload;
  try { payload = JSON.parse(Buffer.from(data, 'base64url').toString()); } catch { return null; }
  if (!payload.exp || Date.now() > payload.exp) return null;        // expired
  if (payload.verified !== true) return null;                       // only a verified outcome is trustworthy
  return payload; // verified + unexpired — the caller MUST still bind payload.transactionId to the ceremony it started
}
```

**Reference verifier — server-side Kotlin / JVM** (standard library + any JSON parser; this uses
`org.json`):

```kotlin
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

data class AkedlyClaim(
    val verified: Boolean,
    val purpose: String?,
    val transactionId: String?,
    val pipelineId: String?,
    val exp: Long                      // milliseconds since epoch
)

fun verifyAkedlyResult(token: String, apiKey: String): AkedlyClaim? {
    if (apiKey.isBlank()) return null                              // fail closed: never HMAC under an empty key
    val prefix = "pkrt1."
    if (!token.startsWith(prefix)) return null
    val segments = token.substring(prefix.length).split(".")
    if (segments.size != 2) return null                            // exactly two segments
    val dataSegment = segments[0]
    val sigSegment = segments[1]
    // Strict, unpadded base64url on both segments — getUrlDecoder() also accepts the padded
    // ("…=") spelling of the same 32-byte HMAC, i.e. an alternate serialization of one signed
    // proof that could slip past a string-keyed replay check. Reject it up front.
    val b64u = Regex("^[A-Za-z0-9_-]+$")
    if (!b64u.matches(dataSegment) || !b64u.matches(sigSegment)) return null

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val expected = mac.doFinal((prefix + dataSegment).toByteArray(Charsets.UTF_8))  // "pkrt1." + dataSegment

    val urlDecoder = Base64.getUrlDecoder()
    val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    val provided = try { urlDecoder.decode(sigSegment) } catch (e: Exception) { return null }
    val dataBytes = try { urlDecoder.decode(dataSegment) } catch (e: Exception) { return null }
    // string-keyed single-use checks must see one canonical serialization
    if (urlEncoder.encodeToString(provided) != sigSegment ||
        urlEncoder.encodeToString(dataBytes) != dataSegment) return null
    if (!MessageDigest.isEqual(expected, provided)) return null     // constant-time

    val payload = try {
        JSONObject(String(dataBytes, Charsets.UTF_8))
    } catch (e: Exception) { return null }
    val exp = payload.optLong("exp", 0L)                           // 0 if missing/non-numeric (no throw)
    if (exp <= 0L || System.currentTimeMillis() > exp) return null // missing/invalid or expired
    return AkedlyClaim(
        verified = payload.optBoolean("verified", false),
        purpose = payload.optString("purpose").ifEmpty { null },
        transactionId = payload.optString("transactionId").ifEmpty { null },
        pipelineId = payload.optString("pipelineId").ifEmpty { null },
        exp = exp
    )
}

// On your sign-in route, after the app POSTs { resultToken }:
//   val claim = verifyAkedlyResult(resultToken, AKEDLY_API_KEY)
//   if (claim != null && claim.verified && claim.transactionId == expectedTxId) createSession(user)
```

Treat the token like a one-time auth code: short-lived (~2 min) and accepted once.

### Local vs on-device testing

The `ceremonyOrigin` decides which auth-gateway runs the WebAuthn ceremony — and therefore the
relying-party (RP) ID the passkey binds to.

- **Android Emulator (local).** Use a system image **with Google Play Services** and a
  configured **screen lock** (PIN / pattern / biometric) — the platform authenticator
  (Credential Manager) refuses to create passkeys without one. Pass
  `ceremonyOrigin = "http://localhost:5174"` to run the ceremony on the local auth-gateway with
  RP=`localhost` (accepted on the emulator). Because the emulator's `localhost` is the emulator
  itself, tunnel the host with **`adb reverse tcp:5174 tcp:5174`** (and `tcp:4100` for your token
  backend) so the ceremony origin stays `localhost`. Do **not** point `ceremonyOrigin` at the host
  alias `10.0.2.2` — over plain HTTP it is not a trustworthy WebAuthn origin and would bind the
  passkey to the wrong RP; `10.0.2.2` is fine for your token backend, but keep the ceremony on
  `localhost`.
- **Real device (prod).** Use the default `ceremonyOrigin` (`https://auth.akedly.io`,
  RP=`akedly.io`) — a `localhost` RP cannot bind on a physical device.

> There is a full end-to-end V1.2 sandbox — web plus all four mobile SDK reference apps, with a
> headless Playwright + Chrome virtual-authenticator gate — for exercising this loop without a
> physical device.

### Without the SDK (open the page yourself)

`AkedlyPasskey` is a thin wrapper. The ceremony is just a URL you open in a Custom Tab /
browser; the result comes back on your deep-link scheme:

```
https://auth.akedly.io/pk?token=<ceremonyToken>&returnUrl=myapp://akedly-passkey
   -> redirects to: myapp://akedly-passkey?verified=true&transactionId=…&resultToken=pkrt1.…
```

`AkedlyPasskey.buildUrl(...)` and `AkedlyPasskey.parseResult(uri)` / `parseResultFromQuery(query)`
are public if you want them without the launcher. For production, prefer signing the
`returnTarget` into the ceremony token server-side via `/auth-options` over the `returnUrl`
query param.

## Related Packages

- **JavaScript**: [`@akedly/shield`](https://www.npmjs.com/package/@akedly/shield)
- **Dart/Flutter**: [`akedly_shield`](https://github.com/Akedly-Org/akedly-shield-dart)
- **Swift (iOS)**: [`AkedlyShield`](https://github.com/Akedly-Org/akedly-shield-swift)
