import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import site.salesmanpro.android.webbridge.WebAppInterface

@Composable
fun MainWebViewScreen(
    webInterface: WebAppInterface,
    modifier: Modifier = Modifier
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val baseUrl = "https://salesmanpro.site"
    val dashboardUrl = "$baseUrl/dashboards"
    // Use the handover as the primary entry point
    val entryUrl = "$baseUrl/api/auth/handover?target=$dashboardUrl"

    val defaultUA = WebSettings.getDefaultUserAgent(context)
    val desktopUA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    val appSpecificUA = "$defaultUA SalesmanProAndroid"

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )

                // 1. Aggressive Cookie Policy
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val urlString = request.url.toString()

                        // Google Auth Bypass
                        if (urlString.contains("accounts.google.com")) {
                            view.settings.userAgentString = desktopUA
                        } else {
                            view.settings.userAgentString = appSpecificUA
                        }
                        return false // Allow WebView to handle the redirect
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()

                        // 2. THE LOOP BREAKER
                        // Your backend sends ?auth=success. If we see this, we are officially in.
                        if (url.contains("auth=success")) {
                            // If we aren't already on the dashboard, force the move
                            if (!url.contains("/dashboards")) {
                                view.loadUrl(dashboardUrl)
                            }
                            return
                        }

                        // 3. Fallback for the "Stuck" Login Page
                        // If we are on the login page but the session cookie actually exists,
                        // it means the redirect failed to trigger. Let's nudge it.
                        if (url.contains("desktop-login")) {
                            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                            if (cookies.contains("next-auth.session-token") ||
                                cookies.contains("__Secure-next-auth.session-token")) {
                                view.loadUrl(dashboardUrl)
                            }
                        }
                    }

                    // Handle SSL errors in dev (optional, remove for production)
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    // Set UA before the first load
                    userAgentString = appSpecificUA
                }

                addJavascriptInterface(webInterface, "AndroidBridge")

                // Initial Load
                loadUrl(entryUrl)
                webView = this
            }
        },
        update = { webView = it }
    )
}