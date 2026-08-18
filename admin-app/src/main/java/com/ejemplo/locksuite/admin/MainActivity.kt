package com.ejemplo.locksuite.admin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ADMIN_PANEL_URL = "https://locksuite-nueva.web.app"
        private const val KEY_LAST_URL = "last_url"
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutOffline: LinearLayout
    private lateinit var btnRetry: Button

    private var backPressedTime: Long = 0
    private var hasError: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        layoutOffline = findViewById(R.id.layoutOffline)
        btnRetry = findViewById(R.id.btnRetry)

        setupSwipeRefresh()
        setupWebView()
        setupBackHandler()

        btnRetry.setOnClickListener {
            layoutOffline.visibility = View.GONE
            webView.visibility = View.VISIBLE
            hasError = false
            loadPanel()
        }

        if (savedInstanceState != null) {
            // restoreState no restaura localStorage/sessionStorage de Firebase Auth,
            // pero sí restaura el historial de navegación y la URL actual.
            // Las cookies (que sí persisten Firebase Auth) ya están en CookieManager.
            webView.restoreState(savedInstanceState)
        } else {
            loadPanel()
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            Color.parseColor("#F1C40F"), // Accent gold
            Color.parseColor("#2C5282")  // Navy light
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1E3E62"))

        swipeRefresh.setOnRefreshListener {
            hasError = false
            webView.reload()
        }

        // Evitar que SwipeRefresh se active cuando el WebView tiene scroll interno.
        // Solo permitir pull-to-refresh cuando el WebView está en el tope del contenido.
        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = webView.scrollY == 0
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Optimización visual para pantallas móviles y pequeñas (< 3 pulgadas)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100

        // Rendimiento y caché
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // Evitar el bloqueo "403 disallowed_useragent" de Google Auth en WebViews
        val defaultUa = settings.userAgentString
        settings.userAgentString = defaultUa.replace("; wv", "")

        // Cookies y sesiones persistentes para Firebase Auth
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#0B192C"))

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasError = false
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false

                if (!hasError) {
                    layoutOffline.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasError = true
                    showOfflineScreen()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Mantener toda la navegación interna dentro del WebView
                return false
            }
        }
    }

    private fun loadPanel() {
        if (!isNetworkAvailable()) {
            showOfflineScreen()
            return
        }
        hasError = false
        layoutOffline.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(ADMIN_PANEL_URL)
    }

    private fun showOfflineScreen() {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        layoutOffline.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutOffline.visibility == View.VISIBLE) {
                    finish()
                    return
                }

                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = currentTime
                        Toast.makeText(
                            this@MainActivity,
                            "Presione atrás nuevamente para salir",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // Limpiar el WebView correctamente para evitar memory leaks:
        // 1. Removerlo de su parent
        // 2. Detener la carga
        // 3. Destruirlo
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
