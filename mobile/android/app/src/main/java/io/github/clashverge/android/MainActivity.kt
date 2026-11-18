package io.github.clashverge.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.github.kr328.clash.common.Global

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: ClashBridge
    private lateinit var pipView: PipTrafficView
    private lateinit var contentRoot: FrameLayout

    /**
     * VPN 授权必须走 startActivityForResult，否则 MIUI 会立即关闭授权弹窗。
     */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ClashVergeService.startTunNow(this)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureVpnThenStart() {
        val prepare = android.net.VpnService.prepare(this)
        if (prepare == null) {
            ClashVergeService.startTunNow(this)
            return
        }
        runOnUiThread { vpnPermissionLauncher.launch(prepare) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Global.init(application)
        ProfileAdapter.init(this)
        VergeAdapter.init(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.databaseEnabled = true
            WebView.setWebContentsDebuggingEnabled(true)

            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/web/", WebViewAssetLoader.AssetsPathHandler(this@MainActivity))
                .build()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    android.util.Log.e(
                        "WebView",
                        "Resource error: ${error?.description} for ${request?.url}",
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val level = consoleMessage?.messageLevel()
                    val msg = consoleMessage?.message()
                    val src = consoleMessage?.sourceId()
                    val line = consoleMessage?.lineNumber()
                    val tag = when (level) {
                        ConsoleMessage.MessageLevel.ERROR -> "WebConsole/E"
                        ConsoleMessage.MessageLevel.WARNING -> "WebConsole/W"
                        else -> "WebConsole"
                    }
                    android.util.Log.println(
                        when (level) {
                            ConsoleMessage.MessageLevel.ERROR -> android.util.Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> android.util.Log.WARN
                            else -> android.util.Log.DEBUG
                        },
                        tag,
                        "$msg ($src:$line)",
                    )
                    return super.onConsoleMessage(consoleMessage)
                }
            }
        }

        bridge = ClashBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        pipView = PipTrafficView(this).apply { visibility = View.GONE }

        val root = FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pipView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        contentRoot = root
        setContentView(root)

        applySystemBarStyle()

        // Android 15 起窗口强制 edge-to-edge，内容会顶到状态栏下面，
        // 这里用系统栏 Insets 给容器加内边距，避免标题栏按钮与状态栏重叠。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        contentRoot.requestApplyInsets()

        requestNotificationPermission()

        webView.loadUrl("https://appassets.androidplatform.net/web/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun applySystemBarStyle() {
        val dark = runCatching {
            org.json.JSONObject(VergeAdapter.getConfig()).optString("theme_mode") != "light"
        }.getOrDefault(true)
        val background = if (dark) 0xFF181A1B.toInt() else 0xFFFFFFFF.toInt()

        window.setBackgroundDrawable(ColorDrawable(background))
        webView.setBackgroundColor(background)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ===== 自定义标题栏按钮 =====

    fun minimizeToHome() {
        moveTaskToBack(true)
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT < 26) return
        if (isInPictureInPictureMode) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .build()
        runCatching { enterPictureInPictureMode(params) }
            .onFailure { android.util.Log.w(TAG, "enter pip failed", it) }
    }

    /**
     * 只退出界面：VPN 是前台服务，进程存活时隧道和后台上报都会继续。
     */
    fun closeApp() {
        finishAndRemoveTask()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            webView.visibility = View.GONE
            pipView.visibility = View.VISIBLE
            pipView.start()
        } else {
            pipView.stop()
            pipView.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        contentRoot.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        current = this
        maybeStartTun()
        notifyVpnConfigChanged()
    }

    /**
     * 用户完成 VPN 授权后回到应用时自动启动，避免开关已打开但服务未启动。
     */
    private fun maybeStartTun() {
        val enabled = try {
            org.json.JSONObject(VergeAdapter.getConfig()).optBoolean("enable_tun_mode", false)
        } catch (_: Throwable) {
            false
        }
        if (!enabled || VpnTunService.isRunning()) return
        if (android.net.VpnService.prepare(this) != null) return

        ClashVergeService.startTunNow(this)
    }

    override fun onDestroy() {
        if (current === this) current = null
        pipView.stop()
        bridge.release()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT

        @Volatile
        private var current: MainActivity? = null

        /**
         * VPN 状态由系统磁贴/通知栏改变时，前端缓存不会自动刷新，需要主动通知。
         */
        fun notifyVpnConfigChanged() {
            val activity = current ?: return
            activity.runOnUiThread {
                activity.webView.evaluateJavascript(
                    "window.__tauriEmitEvent&&window.__tauriEmitEvent('verge://refresh-verge-config',null)",
                    null,
                )
            }
        }
    }
}
