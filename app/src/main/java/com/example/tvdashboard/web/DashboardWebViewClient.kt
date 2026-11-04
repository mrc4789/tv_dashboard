package com.example.tvdashboard.web

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** Cancels invalid TLS resources; only main-document failures stop the dashboard. */
class DashboardWebViewClient(
    private val onLoadFinished: () -> Unit,
    private val onLoadError: (LoadError) -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit
) : WebViewClient() {
    private var failed = false

    // shouldInterceptRequest runs off the UI thread. Never read WebView state there.
    private val requestLock = Any()
    private val requests = LinkedHashMap<String, Boolean>()

    private fun urlKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            Uri.parse(raw).normalizeScheme().buildUpon().fragment(null).build().toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun remember(raw: String?, mainFrame: Boolean) {
        val key = urlKey(raw) ?: return
        synchronized(requestLock) {
            // A URL used as both a document and a resource must remain main-frame.
            val wasMain = requests[key] == true
            if (!requests.containsKey(key) && requests.size >= MAX_TRACKED_REQUESTS) {
                val oldest = requests.keys.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            requests[key] = mainFrame || wasMain
        }
    }

    private fun requestKind(raw: String?): Boolean? {
        val key = urlKey(raw) ?: return null
        return synchronized(requestLock) { requests[key] }
    }

    /** Seed the main document before loadUrl, including errors before onPageStarted. */
    fun beginLoad(url: String) {
        failed = false
        synchronized(requestLock) { requests.clear() }
        remember(url, true)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        remember(url, true)
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true
        // Also records top-level redirects/link navigation when this callback is delivered.
        remember(request.url.toString(), request.isForMainFrame)
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        remember(request.url.toString(), request.isForMainFrame)
        // No fetching, rewriting, or accepting certificates here.
        return null
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (!failed && requestKind(url) == true) onLoadFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        val kind = when (error.errorCode) {
            ERROR_FAILED_SSL_HANDSHAKE -> LoadError.Kind.SSL
            ERROR_TIMEOUT -> LoadError.Kind.TIMEOUT
            else -> LoadError.Kind.NETWORK
        }
        fail(LoadError(kind, "${error.description} (${error.errorCode})", error.errorCode.toString()))
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            fail(LoadError(LoadError.Kind.HTTP_SERVER, errorResponse.statusCode.toString(), errorResponse.statusCode.toString()))
        }
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        // Always block the failing TLS connection, including subresources.
        handler.cancel()

        val failedKey = urlKey(error.url)
        val displayedKey = urlKey(view.url)
        val matchesDisplayedPage = failedKey != null && failedKey == displayedKey
        val kind = requestKind(error.url)
        val isKnownSubresource = kind == false && !matchesDisplayedPage
        Log.w(
            "DashboardSSL",
            "sslError=${error.primaryError}; " +
                    "frame=${if (isKnownSubresource) "resource" else if (kind == true || matchesDisplayedPage) "main" else "unknown"}"
        )

        if (isKnownSubresource) {
            Log.w("DashboardSSL", "Đã chặn tài nguyên phụ lỗi SSL; tiếp tục tải trang chính")
            return
        }

        // SSL callbacks have no isForMainFrame. Only a tracked/displayed document
        // can fail the dashboard; unknown callbacks may be secondary resources.
        if (kind == true || matchesDisplayedPage) {
            fail(LoadError(LoadError.Kind.SSL, "Mã SSL: ${error.primaryError}", error.primaryError.toString()))
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private fun fail(error: LoadError) {
        if (failed) return
        failed = true
        onLoadError(error)
    }

    private companion object {
        const val MAX_TRACKED_REQUESTS = 2048
    }
}
