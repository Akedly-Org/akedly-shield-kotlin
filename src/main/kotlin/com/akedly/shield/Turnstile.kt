package com.akedly.shield

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class AkedlyTurnstile(
    private val context: Context,
    private val bridgeDomain: String = "turnstile.akedly.io"
) {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun getToken(siteKey: String): String {
        return suspendCancellableCoroutine { continuation ->
            val wv = WebView(context).apply {
                visibility = View.GONE
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onToken(token: String) {
                        if (token.isNotEmpty()) {
                            continuation.resume(token)
                        } else {
                            continuation.resumeWithException(
                                AkedlyTurnstileException("Empty token received")
                            )
                        }
                        cleanup()
                    }
                }, "AkedlyBridge")

                webViewClient = WebViewClient()
                loadUrl("https://$bridgeDomain/challenge?sitekey=$siteKey")
            }

            webView = wv

            continuation.invokeOnCancellation {
                cleanup()
            }
        }
    }

    private fun cleanup() {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("AkedlyBridge")
            destroy()
        }
        webView = null
    }
}

class AkedlyTurnstileException(message: String) : Exception(message)
