package com.example.tvdashboard.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tvdashboard.BuildConfig
import com.example.tvdashboard.R
import com.example.tvdashboard.data.PrefsStore
import com.example.tvdashboard.data.UrlValidator
import com.example.tvdashboard.databinding.ActivityMainBinding
import com.example.tvdashboard.net.NetworkMonitor
import com.example.tvdashboard.remote.RemoteEvents
import com.example.tvdashboard.remote.RemoteSync
import com.example.tvdashboard.web.DashboardWebViewClient
import com.example.tvdashboard.web.LoadError
import com.example.tvdashboard.web.ReloadManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Màn hình chính: hiển thị dashboard bằng WebView toàn màn hình.
 * - URL lấy từ PrefsStore (Cài đặt, adb hoặc BuildConfig.DEFAULT_URL).
 * - Chờ mạng trước khi tải; lỗi thì tự thử lại (backoff); reload định kỳ;
 *   dựng lại WebView khi tiến trình render bị crash.
 * - Phím: Menu hoặc giữ OK 3 giây = Cài đặt; Đỏ (PROG_RED) hoặc F5 = tải lại ngay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var reloadManager: ReloadManager
    private val prefs by lazy { PrefsStore(applicationContext) }
    private val networkMonitor by lazy { NetworkMonitor(applicationContext) }

    private var webView: WebView? = null
    private var dashboardClient: DashboardWebViewClient? = null

    private var webViewGeneration = 0L
    private var pageLoading = false
    private var networkStateKnown = false
    private var retryCountdownJob: Job? = null
    private lateinit var networkNotice: TextView
    private var currentUrl: String? = null
    private var remoteMode = false
    private var online = false
    private var pageOk = false
    private var setupPromptShown = false
    private var lastBackPressTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val openSettingsRunnable = Runnable { openSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.tvdashboard.remote.RemoteDiagnostics.initialize(applicationContext)
        com.example.tvdashboard.boot.HomeLauncherSetup.registerTvCandidate(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNetworkNotice()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        setupBackHandling()

        reloadManager = ReloadManager(
            scope = lifecycleScope,
            onReload = { if (online) loadDashboard(resetBackoff = false) },
            onLoadTimeout = {
                if (pageLoading) {
                    handleLoadError(LoadError(LoadError.Kind.TIMEOUT, diagnosticCode = "1"))
                    webView?.stopLoading()
                }
            }
        )

        createWebView()
        handleIntent(intent)
        observeState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- Trạng thái

    private fun observeState() {
        lifecycleScope.launch { prefs.ensureDeviceId() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        prefs.configuredUrlFlow,
                        networkMonitor.isOnline,
                        prefs.remoteSetupFlow
                    ) { url, isOnline, setup ->
                        Triple(url, isOnline, setup.enabled)
                    }
                        .distinctUntilChanged()
                        .collect { (url, isOnline, enabled) ->
                            remoteMode = enabled
                            onUrlOrNetworkChanged(url, isOnline)
                        }
                }
                launch {
                    prefs.remoteSetupFlow.collectLatest { setup ->
                        com.example.tvdashboard.remote.RemoteDiagnostics.onSetupChanged(setup)
                        if (com.example.tvdashboard.remote.DevicePolicySync.canSync(setup)) {
                            networkMonitor.isOnline.collectLatest { connected ->
                                if (connected) coroutineScope {
                                    if (setup.enabled) launch { RemoteSync.poll(prefs) }
                                    launch { com.example.tvdashboard.remote.DevicePolicySync.poll(prefs) }
                                    launch { RemoteEvents.watch(prefs,setup) }
                                }
                            }
                        }
                    }
                }
                launch {
                    prefs.reloadIntervalMinutesFlow.collectLatest { minutes ->
                        reloadManager.runPeriodic(minutes)
                    }
                }
            }
        }
    }

    private fun onUrlOrNetworkChanged(url: String?, isOnline: Boolean) {
        val urlChanged = url != currentUrl
        val regained = networkStateKnown && isOnline && !online
        networkStateKnown = true
        if (isOnline != online) {
            Log.i(TAG, if (isOnline) "Mạng: có kết nối" else "Mạng: mất kết nối")
        }
        online = isOnline
        currentUrl = url
        if (urlChanged) pageOk = false
        if (!isOnline && pageLoading) {
            com.example.tvdashboard.remote.DashboardLoadStatus.set(currentUrl.orEmpty(), "error")
            com.example.tvdashboard.remote.RemoteDiagnostics.record("NETWORK", "1")
        }

        if (url == null) {
            stopRetryCountdown()
            reloadManager.resetBackoff()
            reloadManager.cancelWatchdog()
            pageLoading = false
            pageOk = false
            webView?.stopLoading()
            showNetworkNotice(if (!online) getString(R.string.network_lost_banner) else null)
            if (remoteMode) {
                if (!online) showOfflineStatus()
                else showStatus(getString(R.string.remote_waiting_settings), true, getString(R.string.remote_waiting_detail))
                return
            }
            showStatus(getString(R.string.not_configured_title), false, getString(R.string.not_configured_detail))
            if (!setupPromptShown) {
                setupPromptShown = true
                openSettings()
            }
            return
        }

        if (!online) {
            stopRetryCountdown()
            reloadManager.cancelRetry()
            reloadManager.cancelWatchdog()
            pageLoading = false // Ignore callbacks caused by stopLoading.
            if (!pageOk) webView?.stopLoading()
            showNetworkNotice(getString(R.string.network_lost_banner))
            if (pageOk) hideStatus() else showOfflineStatus()
            return
        }

        if (urlChanged || regained || (!pageOk && !pageLoading)) {
            loadDashboard(resetBackoff = true, reconnecting = regained)
        } else if (pageOk) {
            showNetworkNotice(null)
        }
    }

    private fun loadDashboard(
        resetBackoff: Boolean,
        recreateWebView: Boolean = true,
        reconnecting: Boolean = false
    ) {
        val url = currentUrl ?: return
        if (!online) {
            showOfflineStatus()
            return
        }
        stopRetryCountdown()
        reloadManager.cancelRetry()
        reloadManager.cancelWatchdog()
        if (resetBackoff) reloadManager.resetBackoff()
        pageLoading = false
        if (recreateWebView) {
            destroyWebView()
            createWebView()
        }
        val wv = webView ?: return
        pageOk = false
        pageLoading = true
        showNetworkNotice(if (reconnecting) getString(R.string.network_reconnecting) else null)
        showBusy(getString(if (reconnecting) R.string.network_reconnecting else R.string.loading_dashboard))
        Log.i(TAG, if (reconnecting) "Mạng phục hồi: tải lại dashboard" else "Bắt đầu tải dashboard")
        com.example.tvdashboard.remote.DashboardLoadStatus.set(url, "loading")
        dashboardClient?.beginLoad(url)
        reloadManager.armWatchdog(LOAD_TIMEOUT_MS)
        wv.loadUrl(url)
    }

    /** Tải lại ngay theo yêu cầu của người dùng (phím Đỏ / F5). */
    private fun reloadNow() {
        if (currentUrl == null) {
            openSettings()
            return
        }
        if (!online) {
            Toast.makeText(this, R.string.waiting_network, Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(TAG, "Người dùng yêu cầu tải lại")
        Toast.makeText(this, R.string.reloading_now, Toast.LENGTH_SHORT).show()
        loadDashboard(resetBackoff = true)
    }

    private fun onLoadSuccess() {
        if (!pageLoading || !online) return
        com.example.tvdashboard.remote.DashboardLoadStatus.set(currentUrl.orEmpty(), "loaded")
        com.example.tvdashboard.remote.RemoteDiagnostics.record("LOADED")
        Log.i(TAG, "Tải dashboard thành công")
        pageLoading = false
        pageOk = true
        stopRetryCountdown()
        reloadManager.onLoadSuccess()
        showNetworkNotice(null)
        hideStatus()
    }

    private fun handleLoadError(error: LoadError) {
        if (!pageLoading) return
        com.example.tvdashboard.remote.DashboardLoadStatus.set(currentUrl.orEmpty(), "error")
        com.example.tvdashboard.remote.RemoteDiagnostics.record(error.kind.name, error.diagnosticCode)
        Log.w(TAG, "Lỗi tải trang chính: ${error.kind}; code=${error.diagnosticCode.orEmpty()}")
        pageLoading = false
        pageOk = false
        reloadManager.cancelWatchdog()
        showNetworkNotice(null)
        if (!online) {
            stopRetryCountdown()
            reloadManager.cancelRetry()
            showNetworkNotice(getString(R.string.network_lost_banner))
            showOfflineStatus()
            return
        }
        reloadManager.scheduleRetry()
        stopRetryCountdown()
        val title = when (error.kind) {
            LoadError.Kind.NETWORK -> getString(R.string.dashboard_unreachable)
            LoadError.Kind.TIMEOUT -> getString(R.string.dashboard_no_response)
            else -> getString(R.string.load_error_title)
        }
        retryCountdownJob = lifecycleScope.launch {
            while (online && !pageLoading && !pageOk) {
                val seconds = reloadManager.retryRemainingSeconds()
                if (seconds <= 0) break
                showStatus(
                    title, false,
                    describe(error) + "\n" + getString(R.string.retry_in, seconds) +
                            "\n\n" + getString(R.string.overlay_hint)
                )
                delay(1000)
            }
        }
    }

    private fun stopRetryCountdown() {
        retryCountdownJob?.cancel()
        retryCountdownJob = null
    }

    private fun showOfflineStatus() {
        showStatus(getString(R.string.network_lost_title), false, getString(R.string.network_lost_detail))
    }

    private fun setupNetworkNotice() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        networkNotice = TextView(this).apply {
            textSize = 18f
            setTextColor(getColor(R.color.on_background))
            setBackgroundColor(0xE61F2937.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10))
            gravity = Gravity.CENTER
            isFocusable = false
            isClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(54)
                leftMargin = dp(48)
                rightMargin = dp(48)
            }
        }
        binding.root.addView(networkNotice)
    }

    private fun showNetworkNotice(message: String?) {
        networkNotice.text = message.orEmpty()
        networkNotice.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun describe(error: LoadError): String = when (error.kind) {
        LoadError.Kind.NETWORK -> getString(R.string.error_network, error.detail.orEmpty())
        LoadError.Kind.HTTP_SERVER -> when (error.detail) {
            "401" -> "Website yêu cầu đăng nhập (HTTP 401)."
            "403" -> "Website từ chối quyền truy cập (HTTP 403)."
            "404", "410" -> "Trang không tồn tại hoặc đã bị xóa. Kiểm tra URL trên admin."
            "429" -> "Website giới hạn lượt truy cập (HTTP 429). App sẽ thử lại theo chu kỳ chờ."
            else -> getString(R.string.error_http, error.detail.orEmpty())
        }
        LoadError.Kind.SSL ->
            getString(R.string.error_ssl) +
                    "\n" + error.detail.orEmpty()
        LoadError.Kind.TIMEOUT -> getString(R.string.error_timeout)
    }

    private fun showStatus(title: String, showProgress: Boolean, detail: String? = null) {
        binding.statusTitle.text = title
        binding.statusDetail.text = detail.orEmpty()
        binding.statusDetail.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.statusOverlay.visibility = View.VISIBLE
    }

    /** Trạng thái đang chờ (vòng quay) kèm dòng gợi ý phím để mở Cài đặt. */
    private fun showBusy(title: String) {
        showStatus(title = title, showProgress = true, detail = getString(R.string.overlay_hint))
    }

    private fun hideStatus() {
        binding.statusOverlay.visibility = View.GONE
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        // Chỉ cho debug bằng chrome://inspect ở bản debug
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val generation = ++webViewGeneration
        val client = DashboardWebViewClient(
            onLoadFinished = {
                if (generation == webViewGeneration) {
                    onLoadSuccess()
                }
            },
            onLoadError = { error ->
                if (generation == webViewGeneration) {
                    handleLoadError(error)
                }
            },
            onRendererGone = { didCrash ->
                com.example.tvdashboard.remote.RemoteDiagnostics.record("RENDERER", if (didCrash) "1" else "0")
                if (generation == webViewGeneration) {
                    handleRendererGone(didCrash)
                }
            }
        )

        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(getColor(R.color.background))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
            }
            webViewClient = client
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        binding.webContainer.addView(wv)
        wv.requestFocus() // để dashboard nhận được phím từ remote
        webView = wv
        dashboardClient = client
    }

    private fun destroyWebView() {
        // Vô hiệu hóa callback cũ trước khi stopLoading/destroy.
        webViewGeneration++

        val oldWebView = webView
        webView = null
        dashboardClient = null

        oldWebView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    /** Tiến trình render của WebView bị kill/crash: dựng lại WebView và tải lại. */
    private fun handleRendererGone(didCrash: Boolean) {
        Log.w(TAG, "WebView render process gone (crash=$didCrash), dựng lại WebView")
        binding.root.post {
            if (isDestroyed || isFinishing) return@post
            destroyWebView()
            createWebView()
            pageOk = false
            reloadManager.cancelWatchdog()
            if (online && currentUrl != null) {
                loadDashboard(resetBackoff = true, recreateWebView = false)
            } else if (currentUrl != null) {
                showBusy(getString(R.string.waiting_network))
            }
        }
    }

    // ---------------------------------------------------------------- Cấu hình / điều khiển

    /** Nhận URL từ adb: `am start -n <pkg>/.ui.MainActivity --es url "https://..."`. */
    private fun handleIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) { intent.removeExtra(EXTRA_URL); return }
        val raw = intent.getStringExtra(EXTRA_URL) ?: return
        intent.removeExtra(EXTRA_URL)
        when (val result = UrlValidator.validate(raw)) {
            is UrlValidator.Result.Valid -> lifecycleScope.launch { prefs.saveLocalMode(result.url, prefs.getReloadIntervalMinutes()) }
            is UrlValidator.Result.Invalid -> {
                Log.w(TAG, "URL từ intent không hợp lệ: ${result.error}")
                Toast.makeText(this, R.string.url_from_intent_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Phím tắt (xem docs/KEYS.md):
     * - Menu: mở Cài đặt.
     * - Giữ OK/Enter 3 giây: mở Cài đặt (các lần bấm OK ngắn vẫn chuyển cho dashboard).
     * - Đỏ (PROG_RED) hoặc F5: tải lại dashboard ngay.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                handler.removeCallbacks(openSettingsRunnable)
                if (event.action == KeyEvent.ACTION_UP) openSettings()
                return true
            }
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_F5 -> {
                if (event.action == KeyEvent.ACTION_UP) reloadNow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handler.removeCallbacks(openSettingsRunnable)
                    handler.postDelayed(openSettingsRunnable, SETTINGS_LONG_PRESS_MS)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(openSettingsRunnable)
                }
                val view = webView
                if (view != null && pageOk && event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val copy = KeyEvent(event)
                    val script = "(function(){var r=window.FactoryHubRemote;return !!(r&&r.handleKey&&r.handleKey({" +
                            "action:" + event.action + ",repeat:" + event.repeatCount +
                            ",cancelled:" + event.isCanceled + ",time:" + event.eventTime + "}));})()"
                    // One recognizer in the page. Never send both custom and DOM events.
                    view.evaluateJavascript(script) { handled ->
                        if (handled != "true" && webView === view && !isFinishing && !isDestroyed && hasWindowFocus()) {
                            view.dispatchKeyEvent(copy) // Dialog controls and unrelated websites work normally.
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Ẩn thanh hệ thống, vuốt/bấm phím mới hiện tạm thời. */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Bấm Back hai lần trong BACK_EXIT_WINDOW_MS mới thoát app. */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressTime <= BACK_EXIT_WINDOW_MS) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(
                        this@MainActivity,
                        R.string.press_back_again,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    // ---------------------------------------------------------------- Vòng đời

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        enterImmersiveMode()
    }

    override fun onPause() {
        webView?.evaluateJavascript("if(window.FactoryHubRemote)window.FactoryHubRemote.cancel();", null)
        handler.removeCallbacks(openSettingsRunnable)
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(openSettingsRunnable)
        reloadManager.cancelRetry()
        reloadManager.cancelWatchdog()
        stopRetryCountdown()
        if (pageLoading) {
            pageLoading = false
            webView?.stopLoading()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRetryCountdown()
        destroyWebView()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val EXTRA_URL = "url"
        const val BACK_EXIT_WINDOW_MS = 2_000L
        const val SETTINGS_LONG_PRESS_MS = 3_000L
        const val LOAD_TIMEOUT_MS = 60_000L
    }
}