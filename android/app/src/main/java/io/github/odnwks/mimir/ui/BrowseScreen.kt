package io.github.odnwks.mimir.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.odnwks.mimir.nav.Navigator
import io.github.odnwks.mimir.online.LookupLog
import io.github.odnwks.mimir.online.Net
import io.github.odnwks.mimir.ui.theme.Palette

/**
 * The app's own browser, for a page the owner chose to open from a chat (DuckDuckGo's results when
 * a search was blocked, or a source). It's the owner browsing, so pages work as in any browser;
 * nothing of it is kept: cookies, storage, cache and history are cleared when it closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowseScreen(url: String, nav: Navigator, engine: io.github.odnwks.mimir.standalone.Engine? = null) {
    var site by remember(url) { mutableStateOf(Net.hostOf(url)) }
    var here by remember(url) { mutableStateOf(url) }
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(url) {
        LookupLog.add(LookupLog.Entry(System.currentTimeMillis(), Net.hostOf(url), "opened by you", LookupLog.Route.Phone, 0), io.github.odnwks.mimir.online.ChatMode.Normal)
        onDispose {
            view?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }
            runCatching { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        BackHeader(site, { if (view?.canGoBack() == true) view?.goBack() else nav.back() }, backLabel = "Back")
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.setGeolocationEnabled(false)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String?) { u?.let { site = Net.hostOf(it); here = it } }
                    }
                    loadUrl(url)
                    view = this
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        // One tap: the page open here is read by Mímir, which answers the chat's last question with it.
        val local = engine?.state?.collectAsStateWithLifecycle()?.value
        val canRead = local != null && local.ready && !local.whisper && !local.writing && here.startsWith("https://") &&
            Net.hostOf(here) != io.github.odnwks.mimir.online.Search.SITE
        if (canRead) BigButton(
            READ_THIS_PAGE, Look.Primary, { engine?.readPage(here); nav.closeScreen() },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), height = 52.dp,
        )
    }
}
