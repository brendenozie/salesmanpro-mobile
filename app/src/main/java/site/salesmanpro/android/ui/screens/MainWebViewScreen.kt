package site.salesmanpro.android.ui.screens

import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import site.salesmanpro.android.webbridge.WebAppInterface
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
// --------------------------------
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Neo-Brutalist Shadow Extension
 */
fun Modifier.neoShadow(
    color: Color = Color.Black,
    offsetX: Dp = 4.dp,
    offsetY: Dp = 4.dp
) = this.drawBehind {
    drawRoundRect(
        color = color,
        topLeft = Offset(offsetX.toPx(), offsetY.toPx()),
        size = size,
        cornerRadius = CornerRadius(0f, 0f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWebViewScreen(
    webInterface: WebAppInterface,
    modifier: Modifier = Modifier
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }

    var loadProgress by remember { mutableIntStateOf(0) }
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

//    var webView by remember { mutableStateOf<WebView?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Force save cookies to disk when app moves to background
                CookieManager.getInstance().flush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 1. Error View Layout
    if (errorMessage != null) {
        ConnectionErrorView(
            message = errorMessage!!,
            onRetry = {
                errorMessage = null
                webView?.reload()
            }
        )
    } else {
        // 2. Main WebView with Pull-to-Refresh
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                webView?.reload()
            },
            state = pullToRefreshState,
            modifier = modifier.fillMaxSize()
        ) {
            AndroidView(
                modifier = modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {

                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // 1. Aggressive Cookie Policy
                        // 1. Ensure the CookieManager is globally configured for persistence
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // 2. IMPORTANT: On modern Android, cookies are persistent by default
                        // IF they have an Expiry date. If they don't, they are "Session Cookies."
                        // We can try to force them to persist.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                        }

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true // Required for NextAuth/React storage
                        settings.databaseEnabled = true

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                if (newProgress >= 90) isLoading = false
                            }
                        }

                        webViewClient = object : WebViewClient() {

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?
                            ) {
                                super.onPageStarted(view, url, favicon)
                                // Ensure cookies are synced as soon as we start moving
                                CookieManager.getInstance().flush()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val urlString = request.url.toString()

                                // 1. Google Auth Bypass
                                if (urlString.contains("accounts.google.com")) {
                                    // Update the UA
                                    view.settings.userAgentString = desktopUA

                                    // CRITICAL FIX: Manually load the URL so the new UA is applied to THIS request
                                    view.loadUrl(urlString)

                                    // Return true because we are taking over the loading process
                                    return true
                                }

                                // 2. DETECT THE HANDOVER
                                if (urlString.contains("/api/auth/handover")) {
                                    view.settings.userAgentString = appSpecificUA
                                    view.loadUrl(urlString)
                                    return true
                                }

                                return false
                            }

//                            override fun shouldOverrideUrlLoading(
//                                view: WebView,
//                                request: WebResourceRequest
//                            ): Boolean {
//                                val urlString = request.url.toString()
//
//                                // Google Auth Bypass
//                                if (urlString.contains("accounts.google.com")) {
//                                    view.settings.userAgentString = desktopUA
//                                    return false // Let Google handle its own flow
//                                }
//                                //                        else {
//                                //                            view.settings.userAgentString = appSpecificUA
//                                //                        }
//                                // 2. DETECT THE HANDOVER: If we see our API, load it manually
//                                // This prevents the "Invalid Stream" error by taking control of the transition
//                                if (urlString.contains("/api/auth/handover")) {
//                                    view.settings.userAgentString = appSpecificUA
//                                    view.loadUrl(urlString)
//                                    return true // We've handled it, don't let the OS try to open a browser
//                                }
//
//                                return false // Allow WebView to handle the redirect
//                            }



                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                isRefreshing = false // Stop the spinner
                                errorMessage = null   // Clear errors on success

                                // Pre-emptively set the UA if we are on the login screen
                                if (url.contains("desktop-login")) {
                                    view.settings.userAgentString = desktopUA
                                }
                                // Reset to app-specific UA when we hit the dashboard
                                else if (url.contains("/dashboards")) {
                                    view.settings.userAgentString = appSpecificUA
                                }

                                // Run cookie sync in the background to prevent UI jank
                                Thread {
                                    CookieManager.getInstance().flush()
                                }.start()

                                // Redirect logic from your previous snippet
                                if (url.contains("auth=success") && !url.contains("/dashboards")) {
                                    view.loadUrl(dashboardUrl)
                                }

                                // 2. THE LOOP BREAKER
                                // Your backend sends ?auth=success. If we see this, we are officially in.
                                //                        if (url.contains("auth=success")) {
                                //                            // If we aren't already on the dashboard, force the move
                                //                            if (!url.contains("/dashboards")) {
                                //                                view.loadUrl(dashboardUrl)
                                //                            }
                                //                            return
                                //                        }

                                // 3. Fallback for the "Stuck" Login Page
                                // If we are on the login page but the session cookie actually exists,
                                // it means the redirect failed to trigger. Let's nudge it.
                                if (url.contains("desktop-login")) {
                                    val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                                    if (cookies.contains("next-auth.session-token") ||
                                        cookies.contains("__Secure-next-auth.session-token")
                                    ) {
                                        view.loadUrl(dashboardUrl)
                                    }
                                }
                            }

                            // Handle SSL errors in dev (optional, remove for production)
//                            override fun onReceivedSslError(
//                                view: WebView?,
//                                handler: SslErrorHandler?,
//                                error: android.net.http.SslError?
//                            ) {
////                                if (request?.isForMainFrame == true) {
//                                    errorMessage = "Check your connection and try again."
//                                    isRefreshing = false
////                                }
//                                handler?.proceed()
//                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    errorMessage = "Check your connection and try again."
                                    isRefreshing = false
                                }
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

                        // Disable Safe Browsing for local testing if it's blocking the handover
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            settings.safeBrowsingEnabled = false
                        }

                        // Initial Load
                        loadUrl(entryUrl)
                        webView = this
                    }
                },
                update = { webView = it }
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary // Or your SalesmanPro brand color
                )
            }


            // 2. High-Impact Initial Splash (Fades out quickly)
            AnimatedVisibility(
                visible = isLoading && loadProgress < 25,
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "SALESMANPRO",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        ),
                        modifier = Modifier
                            .border(3.dp, Color.Black)
                            .padding(16.dp)
                            .neoShadow()
                            .background(Color(0xFFF4FF5E)) // Neon Yellow
                    )
                }
            }

        }
    }
}


@Composable
fun ConnectionErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFFF6B6B)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.neoShadow().background(Color.White).border(3.dp, Color.Black).padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("CONNECTION LOST", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black))
                Text(message, textAlign = TextAlign.Center)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(56.dp).neoShadow(color = Color.White)
        ) {
            Text("RETRY", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

}