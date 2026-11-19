package site.salesmanpro.android.ui.screens

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    val initialConnected = remember {
        val activeNet = cm?.activeNetwork
        val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    val connected = remember { mutableStateOf(initialConnected) }

    DisposableEffect(Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connected.value = true
            }
            override fun onLost(network: Network) {
                connected.value = false
            }
        }
        cm?.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
        onDispose { cm?.unregisterNetworkCallback(cb) }
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
    url: String? = null,
    directFallbackUrl: String? = null,
    webInterface: WebAppInterface,
    microphoneEnabled: Boolean = true,
    onToggleMicrophone: ((Boolean) -> Unit)? = null,
    onRequestMicrophonePermission: (((Boolean) -> Unit) -> Unit)? = null,
    onOpenAppSettings: (() -> Unit)? = null,
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
    var showMicSettings by remember { mutableStateOf(false) }
    var micEnabledState by remember(microphoneEnabled) { mutableStateOf(microphoneEnabled) }
    var systemMicGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(webInterface) {
        webInterface.onOpenMicrophoneSettings = {
            systemMicGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            showMicSettings = true
        }
    }

    val pullState = rememberPullToRefreshState()

    val baseUrl = "https://salesmanpro.store"
    val entryUrl = url ?: "$baseUrl/dashboards"
    val offlineSafeUrl = directFallbackUrl ?: entryUrl

    val defaultUA = WebSettings.getDefaultUserAgent(context)
    val appUA = defaultUA.replace("; wv", "") + " SalesmanProAndroid"

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

    /* -------- Dynamic WebView Cache Mode & Offline Handling -------- */
    LaunchedEffect(isOnline) {
        webView?.settings?.cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        if (!isOnline) {
            // Do NOT set a full-screen blocking errorMessage when offline!
            // Allow the local-first POS workspace in WebView + Service Worker to remain interactive.
            isLoading = false
        } else if (errorMessage != null) {
            autoRetry {
                webView?.loadUrl(entryUrl)
                delay(1200)
                errorMessage == null
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        /* -------- Non-Blocking Offline Mode Banner -------- */
        AnimatedVisibility(visible = !isOnline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFD97706))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "OFFLINE MODE • Local sales & receipts active. Auto-syncs when online.",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {

            /* -------- WebView -------- */
            androidx.compose.animation.AnimatedVisibility(
                visible = webVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        if (isOnline) {
                            isRefreshing = true
                            webView?.reload()
                        } else {
                            isRefreshing = false
                        }
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
                                    cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
                                    userAgentString = appUA
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        safeBrowsingEnabled = false
                                    }
                                }

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                                CookieManager.getInstance().flush()

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

                                    override fun onPermissionRequest(request: PermissionRequest?) {
                                        if (request == null) return
                                        val resources = request.resources ?: emptyArray()
                                        val wantsAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                        if (wantsAudio) {
                                            if (!micEnabledState) {
                                                val nonAudio = resources.filter { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                                                if (nonAudio.isNotEmpty()) {
                                                    request.grant(nonAudio)
                                                } else {
                                                    request.deny()
                                                }
                                                return
                                            }
                                            val hasRecordAudio = ContextCompat.checkSelfPermission(
                                                ctx,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasRecordAudio) {
                                                request.grant(resources)
                                            } else if (onRequestMicrophonePermission != null) {
                                                onRequestMicrophonePermission { granted ->
                                                    systemMicGranted = granted
                                                    if (granted) {
                                                        request.grant(resources)
                                                    } else {
                                                        val nonAudio = resources.filter { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                                                        if (nonAudio.isNotEmpty()) {
                                                            request.grant(nonAudio)
                                                        } else {
                                                            request.deny()
                                                        }
                                                    }
                                                }
                                            } else {
                                                request.deny()
                                            }
                                        } else {
                                            request.grant(resources)
                                        }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    private var hasError = false
                                    private var justCompletedAuth = false
                                    private var triedOfflineFallback = false

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        hasError = false

                                        val safeUrl = url ?: ""
                                        val cleanUrl = safeUrl.substringBefore("?").removeSuffix("/")
                                        val cleanBase = baseUrl.removeSuffix("/")

                                        if (cleanUrl.contains("/api/auth/callback/")) {
                                            justCompletedAuth = true
                                        } else if (justCompletedAuth) {
                                            justCompletedAuth = false
                                            val isOriginalScreen = cleanUrl == cleanBase || cleanUrl.endsWith("/login") || cleanUrl.endsWith("/signin")
                                            val hasAuthError = safeUrl.contains("error=")

                                            if (isOriginalScreen && !hasAuthError) {
                                                view?.stopLoading()
                                                view?.loadUrl(entryUrl)
                                                return
                                            }
                                        }

                                        CookieManager.getInstance().flush()
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest
                                    ): Boolean {
                                        val reqUrl = request.url.toString()
                                        if (reqUrl.contains("salesmanpro.store") || reqUrl.contains("salesmanpro.site")) {
                                            CookieManager.getInstance().flush()
                                        }
                                        if (reqUrl.contains("/logout") || (reqUrl.contains("/signin") && !reqUrl.contains("token=") && !reqUrl.contains("callbackUrl="))) {
                                            webInterface.onLogout?.invoke()
                                        }
                                        return false
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        CookieManager.getInstance().flush()
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (!hasError) {
                                            canGoBack = view.canGoBack()
                                            isRefreshing = false
                                            errorMessage = null

                                            val cm = CookieManager.getInstance()
                                            cm.flush()

                                            val cleanUrl = url.substringBefore("?").removeSuffix("/")
                                            val cleanBase = baseUrl.removeSuffix("/")
                                            val isOriginalScreen = cleanUrl == cleanBase || cleanUrl.endsWith("/login") || cleanUrl.endsWith("/signin")

                                            if (isOriginalScreen && isOnline) {
                                                val allCookies = (cm.getCookie(url) ?: "") + " ; " + (cm.getCookie(baseUrl) ?: "")
                                                val hasSession = allCookies.contains("next-auth.session-token")
                                                if (hasSession) {
                                                    view.loadUrl(entryUrl)
                                                }
                                            }
                                        } else {
                                            isLoading = false
                                            isRefreshing = false
                                        }
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        if (request?.isForMainFrame == true) {
                                            val failingUrl = request.url?.toString().orEmpty()
                                            // If starting offline and the /api/auth/handover endpoint failed, switch to cached target URL directly
                                            if (!isOnline && !triedOfflineFallback && failingUrl.contains("/api/auth/handover")) {
                                                triedOfflineFallback = true
                                                view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                                                view?.loadUrl(offlineSafeUrl)
                                                return
                                            }

                                            // If offline, render self-contained offline recovery page in-memory
                                            // Prevents Chromium's net::ERR_INTERNET_DISCONNECTED from ever displaying
                                            if (!isOnline && !failingUrl.contains("/offline")) {
                                                view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                                                val offlineHtml = getEmbeddedAndroidOfflineHtml(baseUrl)
                                                view?.loadDataWithBaseURL(baseUrl, offlineHtml, "text/html", "UTF-8", null)
                                                isLoading = false
                                                isRefreshing = false
                                                return
                                            }

                                            // Only block the screen if online or if even the cached fallback is unavailable
                                            if (isOnline) {
                                                hasError = true
                                                errorMessage = "Check your connection and try again."
                                            }
                                            isRefreshing = false
                                            isLoading = false
                                        }
                                    }

                                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                        if (request?.isForMainFrame == true && isOnline) {
                                            val statusCode = errorResponse?.statusCode ?: 0
                                            if (statusCode >= 500) {
                                                hasError = true
                                                errorMessage = "Server unavailable. Please retry."
                                                isRefreshing = false
                                                isLoading = false
                                            }
                                        }
                                    }

                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
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
                                webInterface.webViewGetter = { this }

                                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                                    val resolvedMime = mimetype ?: "application/pdf"
                                    var fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, resolvedMime)
                                    if (resolvedMime.contains("pdf", ignoreCase = true) && !fileName.endsWith(".pdf", ignoreCase = true)) {
                                        fileName = "$fileName.pdf"
                                    }

                                    if (downloadUrl.startsWith("blob:", ignoreCase = true)) {
                                        val safeFileName = fileName.replace("'", "\\'")
                                        val safeMime = resolvedMime.replace("'", "\\'")
                                        val js = """
                                            (function() {
                                                fetch('$downloadUrl')
                                                    .then(function(res) { return res.blob(); })
                                                    .then(function(blob) {
                                                        var reader = new FileReader();
                                                        reader.onloadend = function() {
                                                            if (window.AndroidBridge && window.AndroidBridge.saveBase64File) {
                                                                window.AndroidBridge.saveBase64File(reader.result, '$safeFileName', '$safeMime');
                                                            }
                                                        };
                                                        reader.readAsDataURL(blob);
                                                    })
                                                    .catch(function(err) {
                                                        console.error('Blob download failed:', err);
                                                    });
                                            })();
                                        """.trimIndent()
                                        evaluateJavascript(js, null)
                                        Toast.makeText(ctx, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
                                    } else if (downloadUrl.startsWith("data:", ignoreCase = true)) {
                                        webInterface.saveBase64File(downloadUrl, fileName, resolvedMime)
                                    } else {
                                        try {
                                            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                            val cookies = CookieManager.getInstance().getCookie(downloadUrl)
                                            if (!cookies.isNullOrEmpty()) {
                                                request.addRequestHeader("Cookie", cookies)
                                            }
                                            if (!userAgent.isNullOrEmpty()) {
                                                request.addRequestHeader("User-Agent", userAgent)
                                            }
                                            request.setMimeType(resolvedMime)
                                            request.setTitle(fileName)
                                            request.setDescription("Downloading from SalesmanPro")
                                            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                                            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                            dm.enqueue(request)
                                            Toast.makeText(ctx, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }

                                post {
                                    // If launching offline, load the cached target workspace directly instead of /api/auth/handover
                                    val initialTarget = if (isOnline) entryUrl else offlineSafeUrl
                                    loadUrl(initialTarget)
                                }

                                webView = this
                            }
                        }
                    )
                }
            }

            /* -------- Error Overlay (Only shown for unrecoverable errors) -------- */
            androidx.compose.animation.AnimatedVisibility(
                visible = errorMessage != null,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ConnectionErrorView(
                    message = errorMessage ?: "",
                    onRetry = {
                        errorMessage = null
                        isLoading = true
                        loadProgress = 0
                        webView?.settings?.cacheMode = if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
                        webView?.loadUrl(if (isOnline) entryUrl else offlineSafeUrl)
                    }
                )
            }

            /* -------- High-Impact Initial Splash -------- */
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoading && loadProgress < 25 && errorMessage == null,
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
                            .background(Color(0xFFF4FF5E))
                    )
                }
            }
            /* -------- Floating Microphone Settings Pill -------- */
            if (!isLoading && errorMessage == null) {
                SmallFloatingActionButton(
                    onClick = {
                        systemMicGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        showMicSettings = true
                    },
                    containerColor = if (micEnabledState && systemMicGranted) Color(0xFF0F172A) else Color(0xFF7F1D1D),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (micEnabledState && systemMicGranted) "🎙 Mic On" else "🎙 Mic Off",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
            }

            if (showMicSettings) {
                AlertDialog(
                    onDismissRequest = { showMicSettings = false },
                    title = { Text("Microphone Settings", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Allow Microphone in App", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        "Enable voice commands & wake word (\"Hey Selly\")",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                Switch(
                                    checked = micEnabledState,
                                    onCheckedChange = { enabled ->
                                        micEnabledState = enabled
                                        onToggleMicrophone?.invoke(enabled)
                                    }
                                )
                            }

                            HorizontalDivider()

                            Text(
                                text = "Android System Permission: " + if (systemMicGranted) "Granted" else "Not Granted",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (systemMicGranted) Color(0xFF10B981) else Color(0xFFEF4444)
                            )

                            if (!systemMicGranted) {
                                Button(
                                    onClick = {
                                        onRequestMicrophonePermission?.invoke { granted ->
                                            systemMicGranted = granted
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Grant Microphone Permission")
                                }
                            }

                            OutlinedButton(
                                onClick = { onOpenAppSettings?.invoke() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open Android App Settings")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showMicSettings = false
                            webView?.reload()
                        }) {
                            Text("Done & Reload")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMicSettings = false }) {
                            Text("Close")
                        }
                    }
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

private fun getEmbeddedAndroidOfflineHtml(baseUrl: String): String {
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
          <title>SalesmanPro Mobile — Offline Mode</title>
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
              font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
              background: #090d16;
              color: #f8fafc;
              min-height: 100vh;
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: center;
              padding: 20px;
              text-align: center;
            }
            .card {
              background: #0f172a;
              border: 1px solid #1e293b;
              border-radius: 24px;
              max-width: 420px;
              width: 100%;
              padding: 32px 20px;
              box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
            }
            .badge {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              background: rgba(239, 68, 68, 0.15);
              border: 1px solid rgba(239, 68, 68, 0.3);
              color: #f87171;
              font-size: 11px;
              font-weight: 700;
              padding: 4px 10px;
              border-radius: 9999px;
              margin-bottom: 16px;
              text-transform: uppercase;
              letter-spacing: 0.05em;
            }
            .badge-dot {
              width: 7px;
              height: 7px;
              border-radius: 50%;
              background: #ef4444;
            }
            .icon-wrap {
              width: 64px;
              height: 64px;
              border-radius: 18px;
              background: rgba(245, 158, 11, 0.12);
              border: 1px solid rgba(245, 158, 11, 0.25);
              display: flex;
              align-items: center;
              justify-content: center;
              margin: 0 auto 16px;
            }
            .icon-wrap svg { width: 32px; height: 32px; stroke: #f59e0b; }
            h1 { font-size: 20px; font-weight: 800; color: #fff; margin-bottom: 8px; }
            p { color: #94a3b8; font-size: 13px; line-height: 1.5; margin-bottom: 20px; }
            .btn {
              display: flex;
              align-items: center;
              justify-content: center;
              width: 100%;
              padding: 14px 20px;
              border-radius: 12px;
              font-size: 14px;
              font-weight: 700;
              cursor: pointer;
              text-decoration: none;
              border: none;
              margin-bottom: 10px;
            }
            .btn-p { background: #f59e0b; color: #000; }
            .btn-s { background: #1e293b; color: #f8fafc; border: 1px solid #334155; }
            .footer-status { margin-top: 16px; font-size: 11px; color: #64748b; }
          </style>
        </head>
        <body>
          <div class="card">
            <div class="badge">
              <span class="badge-dot"></span>
              <span>Terminal Disconnected</span>
            </div>

            <div class="icon-wrap">
              <svg fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
              </svg>
            </div>

            <h1>No Internet Connection</h1>
            <p>
              Mobile terminal is offline. You can retry the connection or switch directly to the local offline workspace.
            </p>

            <button class="btn btn-p" id="btn-retry" onclick="retry()">
              <span>Retry Connection</span>
            </button>

            <a class="btn btn-s" href="/offline">
              <span>Open Offline Terminal</span>
            </a>

            <div class="footer-status" id="status-line">
              Auto-reconnecting as soon as network is detected...
            </div>
          </div>

          <script>
            function retry() {
              const btn = document.getElementById('btn-retry');
              btn.innerText = 'Checking network...';
              window.location.reload();
            }

            window.addEventListener('online', function() {
              const s = document.getElementById('status-line');
              if (s) { s.innerText = '🟢 Connection restored! Reloading...'; s.style.color = '#10b981'; }
              setTimeout(function() { window.location.reload(); }, 400);
            });
          </script>
        </body>
        </html>
    """.trimIndent()
}
