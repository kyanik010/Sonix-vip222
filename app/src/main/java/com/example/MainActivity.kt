package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt
import org.json.JSONObject
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var rootContainer: FrameLayout? = null
    private var nativePlayer: ExoPlayer? = null
    private var nativePlayerView: PlayerView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            val wv = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(0xFF0A0F14.toInt())

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = true
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }

                addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
                addJavascriptInterface(XtreamBridge(this@MainActivity), "XtreamBridge")
                addJavascriptInterface(NativePlaybackBridge(), "NativePlayback")
            }

            webView = wv
            val root = FrameLayout(this)
            root.addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            rootContainer = root
            setContentView(root)

            // Back button handling: routes back to Home or closes modals inside web app
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    webView?.evaluateJavascript(
                        "(function() { if (window.handleAndroidBack) { window.handleAndroidBack(); } else if (window.remoteNavigator) { window.remoteNavigator.handleBackAction(); } })()",
                        null
                    )
                }
            })

            // Load the standalone SONIX TV interface
            wv.loadUrl("file:///android_asset/sonix_tv.html")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class NativePlaybackBridge {
        @android.webkit.JavascriptInterface
        fun play(url: String, left: Float, top: Float, width: Float, height: Float) {
            runOnUiThread {
                try {
                    stopNativePlayer()
                    val density = resources.displayMetrics.density
                    val params = FrameLayout.LayoutParams(
                        (width * density).roundToInt().coerceAtLeast(1),
                        (height * density).roundToInt().coerceAtLeast(1)
                    ).apply {
                        leftMargin = (left * density).roundToInt()
                        topMargin = (top * density).roundToInt()
                    }
                    val player = ExoPlayer.Builder(this@MainActivity).build()
                    val view = PlayerView(this@MainActivity).apply {
                        useController = false
                        setShutterBackgroundColor(Color.BLACK)
                        setBackgroundColor(Color.BLACK)
                        this.player = player
                    }
                    nativePlayer = player
                    nativePlayerView = view
                    rootContainer?.addView(view, params)
                    view.bringToFront()
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                webView?.evaluateJavascript("window.onNativePlaybackReady && window.onNativePlaybackReady()", null)
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            stopNativePlayer()
                            val message = JSONObject.quote("فشل تشغيل بث MPEG-TS: " + (error.message ?: "خطأ غير معروف"))
                            webView?.evaluateJavascript("window.onNativePlaybackError && window.onNativePlaybackError($message)", null)
                        }
                    })
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                    player.prepare()
                    player.playWhenReady = true
                } catch (error: Exception) {
                    stopNativePlayer()
                    val message = JSONObject.quote(error.message ?: "تعذر تشغيل البث")
                    webView?.evaluateJavascript("window.onNativePlaybackError && window.onNativePlaybackError($message)", null)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun stop() { runOnUiThread { stopNativePlayer() } }

        @android.webkit.JavascriptInterface
        fun toggle() {
            runOnUiThread {
                nativePlayer?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }

        @android.webkit.JavascriptInterface
        fun isPlaying(): Boolean = nativePlayer?.isPlaying == true
    }

    private fun stopNativePlayer() {
        nativePlayerView?.let { rootContainer?.removeView(it) }
        nativePlayerView = null
        nativePlayer?.release()
        nativePlayer = null
    }

    class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun closeApp() {
            activity.runOnUiThread {
                activity.finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val wv = webView ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                dispatchWebKey("ArrowUp")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                dispatchWebKey("ArrowDown")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                dispatchWebKey("ArrowLeft")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                dispatchWebKey("ArrowRight")
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dispatchWebKey("Enter")
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                wv.evaluateJavascript(
                    "(function() { if (window.handleAndroidBack) { window.handleAndroidBack(); } else if (window.remoteNavigator) { window.remoteNavigator.handleBackAction(); } })()",
                    null
                )
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dispatchWebKey(keyName: String) {
        val js = """
            (function() {
                var event = new KeyboardEvent('keydown', {
                    key: '$keyName',
                    code: '$keyName',
                    bubbles: true,
                    cancelable: true
                });
                window.dispatchEvent(event);
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        nativePlayer?.play()
        webView?.evaluateJavascript(
            "(function() { if (typeof window.ensureHomeScreen === 'function') { window.ensureHomeScreen(); } })()",
            null
        )
    }

    override fun onPause() {
        super.onPause()
        webView?.evaluateJavascript(
            "(function() { if (window.videoPlaybackController) { window.videoPlaybackController.pause(); } })()",
            null
        )
        nativePlayer?.pause()
        webView?.onPause()
    }

    override fun onDestroy() {
        stopNativePlayer()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
