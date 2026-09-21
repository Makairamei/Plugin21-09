package com.sad25kag.animesail

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudflare Turnstile for AnimeSail (episode + /utils/player Lokal).
 * Gate often returns HTTP 200 with "Loading.." — must detect body, not only 403.
 */
class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie(domainUrl, "_as_ipin_lc=id-ID; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_tz=Asia/Jakarta; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_ct=ID; path=/; SameSite=Strict")
        cookieManager.flush()

        fun isGateBody(body: String): Boolean {
            val h = body.lowercase()
            if (h.length > 40000) return false // real page
            return h.contains("challenges.cloudflare.com/turnstile") ||
                h.contains("cf-turnstile") ||
                (h.contains("loading..") && h.contains("turnstile")) ||
                (h.contains("<title>loading") && h.contains("turnstile"))
        }

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (existingCookies.contains(targetCookie) || existingCookies.contains("cf_clearance")) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build()
            )
            if (response.code != 403 && response.code != 503) {
                val peek = runCatching { response.peekBody(8192).string() }.getOrDefault("")
                if (!isGateBody(peek)) return response
            }
            response.close()
            // stale cookie — clear and re-solve
            cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0; path=/; Secure")
            cookieManager.setCookie(domainUrl, "cf_clearance=; Max-Age=0; path=/; Secure")
            cookieManager.flush()
        }

        val context = CloudStreamApp.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        val userAgentRef = AtomicReference(
            originalRequest.header("User-Agent")
                ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        val webViewRef = AtomicReference<WebView?>(null)

        handler.post {
            val wv = WebView(context)
            webViewRef.set(wv)
            cookieManager.setAcceptThirdPartyCookies(wv, true)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = userAgentRef.get()
            }
            userAgentRef.set(wv.settings.userAgentString)
            wv.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    sslHandler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    sslHandler?.proceed()
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    cookieManager.flush()
                }
            }
            wv.loadUrl(url)
        }

        // Wait up to 45s for turnstile / clearance cookie
        for (i in 0 until 45) {
            Thread.sleep(1000)
            val cookies = cookieManager.getCookie(domainUrl) ?: ""
            if (cookies.contains(targetCookie) || cookies.contains("cf_clearance")) {
                cookieManager.flush()
                break
            }
        }

        handler.post {
            webViewRef.getAndSet(null)?.apply {
                stopLoading()
                destroy()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        val finalUA = userAgentRef.get()

        return chain.proceed(
            originalRequest.newBuilder()
                .apply { if (finalUA.isNotBlank()) header("User-Agent", finalUA) }
                .header("Cookie", finalCookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
        )
    }
}
