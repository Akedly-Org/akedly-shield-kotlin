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

## The cross-SDK contract (identical in swift / dart / js — do not diverge)

1. **A verified result MUST carry a `resultToken`.** `verified = verifiedRaw && !resultToken.isNullOrBlank()`
   (`Passkey.kt:167`). A bare `?verified=true` with no token is reported `verified = false`,
   `reason = "no_proof"` — never as a trusted success. **Fail closed. This is the whole security
   property of the relayed result** — the redirect arrives over a custom scheme any local app can
   forge, so the token is the only real proof.
2. **The relayed signal is not authoritative.** The customer confirms a sign-in by sending
   `resultToken` to their own backend, which verifies it **offline** by recomputing an HMAC with
   their Akedly API key. No polling, no server-to-server callback needed.
3. **`reason` vocabulary:** `null` when verified, else `"no_proof"` or `"failed"`. A server `code`
   param is reserved in the redirect contract but `/pk` never sends one today.
   ⚠️ Swift and Dart document an `ineligible` value that **no code can emit**, and the JS SDK emits
   `"failed"` without documenting it. Do not copy either mistake into this repo.

## Live task — the shipped launcher does NOT meet the requirement (OI-G)

`AkedlyPasskey.launch()` (`Passkey.kt:89-106`) opens the ceremony with a plain
`Intent(Intent.ACTION_VIEW, …)`. **Chrome Custom Tabs is required, not optional** — it is what makes
the V2 flow work, and the equivalent rule holds on every platform (iOS uses
`ASWebAuthenticationSession`, Flutter delegates to both via `flutter_web_auth_2`). Right now Custom
Tabs appears only as an optional recipe in the README, and `androidx.browser` is not even declared as
a dependency.

The fix is a dependency plus a `launch()` body swap to `CustomTabsIntent`:

- Keep the `ActivityNotFoundException → AkedlyPasskeyException` fallback — a device with no browser
  must still fail cleanly so the caller can fall back to OTP, not crash.
- `buildUrl` and `parseResult` do not change.
- **The cancellation contract does not change either, and the KDoc must keep saying so.** A Custom
  Tab is still fire-and-forget: it cannot signal a user dismissal back to the caller. If the user
  abandons the browser, no redirect fires and no result is ever produced — the caller detects that
  from its redirect Activity's lifecycle. `/pk` has no cancel redirect of its own. (This is the one
  real behavioural difference from iOS, where `ASWebAuthenticationSession` *does* report a cancel.)
- Update the KDoc at `Passkey.kt:71-73`, which currently tells customers a plain VIEW intent is what
  ships and Custom Tabs is the upgrade. After the fix that sentence is backwards.

## Gotchas

- `Uri.getQueryParameter` throws on an opaque (non-hierarchical) `Uri` — e.g. a hostile explicit
  intent carrying `myapp:akedly-passkey?verified=true`. `parseResult` already catches this and
  degrades to a failed result. Do not "simplify" the try/catch away.
- `URLDecoder.decode` throws on malformed percent-encoding (`verified=%`). `parseResultFromQuery`
  skips the bad pair rather than throwing; a missing `verified` then yields `verified=false`.
- The ceremony must run on the **akedly.io origin** in the system browser, so platform passkeys
  (Credential Manager) work. Never move it into a WebView — passkeys will not work there.
