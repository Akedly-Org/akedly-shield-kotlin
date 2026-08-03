# AGENTS.md — akedly-shield-kotlin

Android/Kotlin client SDK for Akedly's **V1.2 secure REST API**. Three independent pieces under
`src/main/kotlin/com/akedly/shield/`:

| File | What it does |
|---|---|
| `Solver.kt` | Proof-of-work solver — finds a nonce whose `sha256(challenge + ":" + nonce)` has N leading zeros |
| `Turnstile.kt` | Cloudflare Turnstile helper |
| `Passkey.kt` | Hosted V1.2 passkey ceremony at `auth.akedly.io/pk` |

Backend lives in a separate repo (`Akedly`). This SDK never talks to Akedly directly on the
customer's behalf — the customer's own backend proxies, holding the API key.

## Build and test

```bash
./gradlew test          # JVM unit tests, src/test/kotlin/
./gradlew build
```

**Local Gradle is currently blocked** by an AGP 8.2.2 vs cmdline-tools SDK-repo-XML v4 mismatch, and
there is **no CI in this repo**. So: never claim a compile or a test run you did not actually see
succeed. If you cannot build, say so plainly and state what you verified by reading instead.

## Conventions

- No code comments **except** KDoc. This repo is a public SDK — the KDoc on every public symbol is
  the contract customers read, so it is load-bearing and must stay accurate. If you change behaviour,
  change the KDoc in the same edit. Ordinary inline `//` narration inside function bodies is not
  wanted; the existing inline comments all explain a non-obvious *why* (e.g. why a parser swallows an
  exception) — match that bar or omit.
- Public API is `object AkedlyPasskey` with `@JvmStatic` / `@JvmOverloads` for Java callers. Keep it.
- Pure functions stay free of Android dependencies so they are JVM-unit-testable — `buildUrl` and
  `parseResultFromQuery` are deliberately Android-free, with `parseResult(Uri)` as the thin Android
  convenience over them. Preserve that split.
- Parsers are fed hostile external input (a redirect `Uri` any app can fire at the deep-link
  Activity). They must **never throw** — return a failed result instead.

## The cross-SDK contract (swift / dart / js)

**Clauses 1–2 are identical across all four SDKs — do not diverge.** Clause 3's `reason`
vocabulary is deliberately **per-platform** and is NOT expected to match: Swift carries seven
values, Dart five, and this SDK two, because a Custom Tab cannot signal a user cancel at all.
Aligning them would mean inventing values a platform cannot actually produce.

**`ceremonyOrigin` must be a bare origin** — `scheme://host[:port]`, no path, no trailing slash.
The four SDKs normalize it differently (JS reduces to a true origin because it reuses the value in
the `event.origin` equality check; the natives only prefix a URL), so a path-carrying or
multi-slash value behaves differently per platform. It fails visibly — the ceremony 404s — so this
is a documented input contract, not a code divergence to "fix".

1. **A verified result MUST carry a `resultToken`.** `verified = verifiedRaw && !resultToken.isNullOrBlank()`
   (in `AkedlyPasskeyCeremony.parseResult`'s field mapping). A bare `?verified=true` with no token is reported `verified = false`,
   `reason = "no_proof"` — never as a trusted success. **Fail closed. This is the whole security
   property of the relayed result** — the redirect arrives over a custom scheme any local app can
   forge, so the token is the only real proof.
2. **The relayed signal is not authoritative.** The customer confirms a sign-in by sending
   `resultToken` to their own backend, which verifies it **offline** by recomputing an HMAC with
   their Akedly API key. No polling, no server-to-server callback needed.
3. **`reason` vocabulary:** `null` when verified, else `"no_proof"` or `"failed"`. A server `code`
   param is reserved in the redirect contract but `/pk` never sends one today.
   ⚠️ Swift, Dart and JS all documented an `ineligible` value **no server ever sends** — fixed
   2026-07-28 in all three, along with READMEs that omitted `no_proof` and `failed`, the values that
   actually fire. Do not reintroduce any of that here. Precisely: the native SDKs *can* surface
   `ineligible` by server-`code` passthrough; it is unreachable only because no server sends that
   code (the real set is `NO_PASSKEY`, `PASSKEY_DISABLED`, `INSUFFICIENT_QUOTA`, `BILLING_FAILED`,
   `CANCELLED`, `FAILED`).

## Custom Tabs — ✅ SHIPPED in `da7ece7` (OI-G closed). Preserve these constraints.

`AkedlyPasskey.launch()` uses `CustomTabsIntent` and the module declares
`androidx.browser:browser:1.7.0`. **An earlier version of this section said the opposite** — that
the launcher was still a plain `Intent.ACTION_VIEW` and `androidx.browser` was undeclared, and it
ordered a KDoc update that had already happened. Both claims were false at `da7ece7`. Codex reads
this file *instead of* `CLAUDE.md`, so leaving that here meant the next run would inherit "the fix is
pending" as ground truth and redo or misreport it. Do not reseed it.

What must be preserved when touching `launch()`:

- **The `ActivityNotFoundException → AkedlyPasskeyException` fallback.** A device with no browser
  must fail cleanly so the caller falls back to OTP, not crash.
- **`androidx.browser` stays at `implementation` scope in `build.gradle.kts` — never `compileOnly`.**
  `compileOnly` drops it at runtime, so `CustomTabsIntent.Builder()` (built *outside*
  the `try` in `launch()`) throws `NoClassDefFoundError`. That is an `Error`, so the `ActivityNotFoundException` catch
  above would not intercept it even if it were inside the block — the promised typed failure becomes an
  unhandled crash, and the OTP fallback never runs.
- **The akedly.io-origin / no-WebView rule.** Platform passkeys do not work in a `WebView`.
- **`buildUrl` and `parseResult` do not change** — they are the pure, JVM-testable half.
- **The cancellation contract**, which is the one real behavioural difference from iOS (where
  `ASWebAuthenticationSession` *does* report a cancel). Get the detection point right: **the signal
  lives on the CALLING Activity, not the redirect Activity.** The redirect Activity only exists
  *because* a redirect arrived (the README's manifest snippet makes it `singleTask`), so on abandonment it is never
  instantiated and watching its lifecycle detects nothing — the user waits forever with no OTP
  fallback. Detect a still-set "in flight" flag on the calling Activity's `onResume`. The KDoc on `launch()`
  says this correctly; keep the two in step.
- **`FLAG_ACTIVITY_NEW_TASK` is conditional on purpose** (in `launch()`, added at triage after the
  S3 review): an `Activity` context opens the tab *inside* the caller's task, which is the whole
  point of Custom Tabs. Adding the flag unconditionally launches a separate task — API-shaped like a
  Custom Tab, UX-shaped like the old browser kick-out. Non-Activity contexts still get the flag,
  because an Application context without it throws `AndroidRuntimeException`, which would escape the
  `ActivityNotFoundException` catch and crash the caller.

**Build constraint:** `androidx.browser:browser:1.7.0`'s AAR requires `minCompileSdk=34`, and this
module sits at exactly 34 — **zero margin. Do not lower `compileSdk`.**

⚠️ **None of this has ever been compiled** (OI-B): no JDK/Android SDK is available in this
environment. The launcher was verified by reading and by bytecode inspection of the dependency —
raising confidence, but **not a compile, and it must never be relayed as one.**

## Gotchas

- An opaque (non-hierarchical) `Uri` may not expose an encoded query. `parseResult` delegates every
  available encoded query to the same strict raw parser and otherwise degrades to a failed result.
- `URLDecoder.decode` throws on malformed percent-encoding (`verified=%`). `parseResultFromQuery`
  fails the entire result rather than accepting a partial callback or throwing.
- The ceremony must run on the **akedly.io origin** in the system browser, so platform passkeys
  (Credential Manager) work. Never move it into a WebView — passkeys will not work there.
