# AkedlyShield (Kotlin)

Client-side PoW solver and Turnstile helper for Akedly Shield V1.2 (Android/JVM).

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

## Related Packages

- **JavaScript**: [`@akedly/shield`](../js/)
- **Dart/Flutter**: [`akedly_shield`](../dart/)
- **Swift (iOS)**: [`AkedlyShield`](../swift/)
