package site.salesmanpro.android.ui.screens

import android.graphics.Picture
import android.graphics.drawable.PictureDrawable
import android.net.*
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.*
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlin.math.pow
import site.salesmanpro.android.webbridge.WebAppInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

/* ---------------- Shadow ---------------- */
fun Modifier.neoShadow(
    color: Color = Color.Black,
    offsetX: Dp = 4.dp,
    offsetY: Dp = 4.dp
) = drawBehind {
    drawRoundRect(
        color = color,
        topLeft = Offset(offsetX.toPx(), offsetY.toPx()),
        size = size,
        cornerRadius = CornerRadius(0f, 0f)
    )
}

/* ---------------- Network Observer ---------------- */
@Composable
fun rememberNetworkState(): State<Boolean> {
    val context = LocalContext.current
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val connected = remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connected.value = true
            }
            override fun onLost(network: Network) {
                connected.value = false
            }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return connected
}

/* ---------------- Auto Retry ---------------- */
suspend fun autoRetry(
    maxAttempts: Int = 5,
    baseDelayMs: Long = 1000,
    block: suspend () -> Boolean
) {
    repeat(maxAttempts) { attempt ->
        if (block()) return
        delay(baseDelayMs * 2.0.pow(attempt).toLong())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainWebViewScreen(
    webInterface: WebAppInterface,
    modifier: Modifier = Modifier,
    onPickFiles: (
        mimeTypes: Array<String>,
        callback: (Array<Uri>) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    var webView: WebView? by remember { mutableStateOf(null) }

    val isOnline by rememberNetworkState()

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    val webVisible by remember { mutableStateOf(true) }

    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }

    var cachedPicture by remember { mutableStateOf<Picture?>(null) }

    val pullState = rememberPullToRefreshState()

    val baseUrl = "https://auth.salesmanpro.site"
    val dashboardUrl = "$baseUrl/dashboards"
    val entryUrl = "$baseUrl/api/auth/handover?target=$dashboardUrl"

    val defaultUA = WebSettings.getDefaultUserAgent(context)
    val desktopUA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/116 Safari/537.36"
    val appUA = "$defaultUA SalesmanProAndroid"

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                CookieManager.getInstance().flush()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler { if (canGoBack) webView?.goBack() }

    /* -------- Offline detection -------- */
    LaunchedEffect(isOnline) {
        if (!isOnline) {
            errorMessage = "You're offline. Check your internet connection."
            isLoading = false // Stop loading so splash hides
        } else if (errorMessage != null) {
            autoRetry {
                webView?.loadUrl(entryUrl)
                delay(1200)
                errorMessage == null
            }
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

        /* -------- Cached offline snapshot -------- */
        if (!isOnline && cachedPicture != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ImageView(context).apply {
                        setImageDrawable(PictureDrawable(cachedPicture!!))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                }
            )
        }

        /* -------- WebView -------- */
        AnimatedVisibility(
            visible = webVisible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    webView?.reload()
                },
                state = pullState,
                modifier = Modifier.fillMaxSize()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {

                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                userAgentString = appUA
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    safeBrowsingEnabled = false
                                }
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            CookieManager.getInstance().flush()

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                    if (newProgress >= 90) isLoading = false
                                }
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {

                                    val mimeTypes = fileChooserParams
                                        ?.acceptTypes
                                        ?.filter { it.isNotBlank() }
                                        ?.toTypedArray()
                                        ?: arrayOf("*/*")

                                    onPickFiles(mimeTypes) { uris ->
                                        filePathCallback?.onReceiveValue(uris)
                                    }

                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                // ✅ Add a flag to track errors for the current load
                                private var hasError = false

                                // ✅ Reset the flag every time a new page starts loading
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    hasError = false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val url = request.url.toString()

                                    // 1. Google OAuth: Requires a Desktop UA.
                                    if (url.contains("accounts.google.com")) {
                                        if (view.settings.userAgentString != desktopUA) {
                                            view.settings.userAgentString = desktopUA
                                            view.loadUrl(url)
                                            return true
                                        }
                                        return false
                                    }

                                    // 2. Returning to ecosystem: Restore the App UA.
                                    if (url.contains("salesmanpro.site")) {
                                        if (view.settings.userAgentString != appUA) {
                                            view.settings.userAgentString = appUA
                                            view.loadUrl(url)
                                            return true
                                        }
                                        return false
                                    }

                                    return false
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    // ✅ Only clear errors and save state if the load was SUCCESSFUL
                                    if (!hasError) {
                                        canGoBack = view.canGoBack()
                                        isRefreshing = false
                                        errorMessage = null
                                        cachedPicture = view.capturePicture()

                                        CookieManager.getInstance().flush()
                                        val cm = CookieManager.getInstance()
                                        Log.d("COOKIE_AUTH", cm.getCookie("https://auth.salesmanpro.site") ?: "NONE")
                                        Log.d("COOKIE_MAIN", cm.getCookie("https://salesmanpro.site") ?: "NONE")

                                        if (url.contains("auth=success") && !url.contains("/dashboards")) {
                                            view.loadUrl(dashboardUrl)
                                        }
                                    } else {
                                        // Ensure loading indicators stop if it finished with an error
                                        isLoading = false
                                        isRefreshing = false
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        hasError = true
                                        errorMessage = "Check your connection and try again."
                                        isRefreshing = false
                                        isLoading = false
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        hasError = true
                                        errorMessage = "Server unavailable. Please retry."
                                        isRefreshing = false
                                        isLoading = false
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    hasError = true
                                    errorMessage = "Secure connection failed."
                                    isRefreshing = false
                                    isLoading = false
                                    handler?.cancel()
                                }
                            }

                            addJavascriptInterface(
                                webInterface,
                                "AndroidBridge"
                            )

                            post {
                                loadUrl(entryUrl)
                            }

                            webView = this
                        }
                    }
                )
            }
        }

        /* -------- Error Overlay -------- */
        AnimatedVisibility(
            visible = errorMessage != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ConnectionErrorView(
                message = errorMessage ?: "",
                onRetry = {
                    // ✅ Instantly reject the retry if the device knows it has no network
                    if (!isOnline) {
                        errorMessage = "You're still offline. Turn on Wi-Fi or cellular data."
                        isLoading = false
                    } else {
                        errorMessage = null
                        isLoading = true
                        loadProgress = 0
                        webView?.loadUrl(entryUrl)
                    }
                }
            )
        }

        /* -------- High-Impact Initial Splash -------- */
        AnimatedVisibility(
            visible =  isLoading && loadProgress < 25 && errorMessage == null,
            modifier = Modifier.fillMaxSize(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SALESMANPRO",
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

/* ---------------- Error UI ---------------- */

@Composable
fun ConnectionErrorView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFF6B6B))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .neoShadow()
                .background(Color.White)
                .border(3.dp, Color.Black)
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "CONNECTION LOST",
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                Text(message, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .neoShadow(color = Color.White)
        ) {
            Text("RETRY", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
