package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.ColorFilter
import android.graphics.Canvas
import android.net.Uri
import android.view.ViewGroup
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
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
    private var nativePlayerView: TextureView? = null
    @Volatile private var nativePlayerActive = false
    @Volatile private var nativePlayerPlaying = false
    private var secondaryAudioPlayer: ExoPlayer? = null
    private val externalAudioHandler = Handler(Looper.getMainLooper())
    private var externalAudioRetryRunnable: Runnable? = null
    private var externalAudioRetryAttempt = 0
    private var externalAudioGeneration = 0L
    private var externalAudioSourceUrl = ""
    @Volatile private var externalAudioActive = false
    private var externalAudioSyncOffsetMs = 0L
    private var externalAudioVolume = 1f
    private var nativeVideoVolumeBeforeExternalAudio = 1f
    private var nativeVideoMuted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            val wv = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.TRANSPARENT)

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
            val root = FrameLayout(this).apply { background = SonixBackgroundDrawable() }
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
                    val mediaUri = Uri.parse(url.trim())
                    require((mediaUri.scheme == "http" || mediaUri.scheme == "https") && !mediaUri.host.isNullOrBlank()) {
                        "رابط البث غير صالح"
                    }
                    val container = rootContainer ?: throw IllegalStateException("واجهة المشغل غير جاهزة")
                    val density = resources.displayMetrics.density
                    val params = FrameLayout.LayoutParams(
                        (width * density).roundToInt().coerceAtLeast(1),
                        (height * density).roundToInt().coerceAtLeast(1)
                    ).apply {
                        leftMargin = (left * density).roundToInt()
                        topMargin = (top * density).roundToInt()
                    }

                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent("SONIX-TV/1.0")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(30000)
                    val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)
                    val player = ExoPlayer.Builder(this@MainActivity)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .build()
                    val textureView = TextureView(this@MainActivity).apply {
                        // TextureView does not support background drawables. Keep its
                        // surface opaque and let ExoPlayer render directly into it.
                        isOpaque = true
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }

                    nativePlayer = player
                    nativePlayerView = textureView
                    nativePlayerActive = true
                    nativePlayerPlaying = false
                    player.volume = if (externalAudioActive || nativeVideoMuted) 0f else 1f
                    // Keep the native video surface behind the transparent WebView viewport,
                    // so the existing HTML controls and overlays remain interactive.
                    container.addView(textureView, 0, params)
                    player.setVideoTextureView(textureView)
                    textureView.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                        android.util.Log.d(
                            "SONIXSurface",
                            "TextureView layout=${right - left}x${bottom - top} shown=${view.isShown} attached=${view.isAttachedToWindow} opaque=${view.isOpaque}"
                        )
                    }
                    textureView.post {
                        android.util.Log.d(
                            "SONIXSurface",
                            "TextureView post-layout=${textureView.width}x${textureView.height} shown=${textureView.isShown} attached=${textureView.isAttachedToWindow}; requested=${params.width}x${params.height} at ${params.leftMargin},${params.topMargin}"
                        )
                    }

                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            android.util.Log.d(
                                "SONIXSurface",
                                "playerState=${playbackState} videoFormat=${player.videoFormat?.width}x${player.videoFormat?.height} texture=${textureView.width}x${textureView.height} shown=${textureView.isShown}"
                            )
                            when (playbackState) {
                                Player.STATE_BUFFERING -> webView?.evaluateJavascript(
                                    "window.onNativePlaybackBuffering && window.onNativePlaybackBuffering()", null)
                                Player.STATE_READY -> webView?.evaluateJavascript(
                                    "window.onNativePlaybackReady && window.onNativePlaybackReady()", null)
                                Player.STATE_ENDED -> webView?.evaluateJavascript(
                                    "window.onNativePlaybackEnded && window.onNativePlaybackEnded()", null)
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            android.util.Log.d(
                                "SONIXSurface",
                                "onVideoSizeChanged=${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio} unappliedRotation=${videoSize.unappliedRotationDegrees}"
                            )
                        }

                        override fun onRenderedFirstFrame() {
                            android.util.Log.i(
                                "SONIXSurface",
                                "FIRST_FRAME_RENDERED texture=${textureView.width}x${textureView.height} shown=${textureView.isShown}"
                            )
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            nativePlayerPlaying = isPlaying
                            webView?.evaluateJavascript(
                                "window.onNativePlaybackStateChanged && window.onNativePlaybackStateChanged(" + isPlaying + ")", null)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val detail = buildString {
                                append("خطأ المشغل: ")
                                append(error.errorCodeName)
                                error.message?.takeIf { it.isNotBlank() }?.let {
                                    append(" — ")
                                    append(it)
                                }
                                var cause = error.cause
                                var depth = 0
                                while (cause != null && depth < 3) {
                                    cause.message?.takeIf { it.isNotBlank() }?.let {
                                        append(" — ")
                                        append(it)
                                    }
                                    cause = cause.cause
                                    depth++
                                }
                            }
                            android.util.Log.e("SONIXPlayback", detail, error)
                            stopNativePlayer()
                            val message = JSONObject.quote(detail)
                            webView?.evaluateJavascript(
                                "window.onNativePlaybackError && window.onNativePlaybackError(" + message + ")", null)
                        }
                    })

                    val extension = mediaUri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase()
                        .orEmpty()
                    val mimeType = when (extension) {
                        "m3u8" -> MimeTypes.APPLICATION_M3U8
                        "mpd" -> MimeTypes.APPLICATION_MPD
                        else -> null
                    }
                    val mediaItem = MediaItem.Builder()
                        .setUri(mediaUri)
                        .apply { if (mimeType != null) setMimeType(mimeType) }
                        .build()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.playWhenReady = true
                } catch (error: Exception) {
                    stopNativePlayer()
                    val message = JSONObject.quote(error.message ?: "تعذر تشغيل البث")
                    webView?.evaluateJavascript(
                        "window.onNativePlaybackError && window.onNativePlaybackError(" + message + ")", null)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun stop() { runOnUiThread { stopNativePlayer() } }

        @android.webkit.JavascriptInterface
        fun toggle() {
            runOnUiThread {
                nativePlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        secondaryAudioPlayer?.pause()
                    } else {
                        it.play()
                        if (externalAudioActive) secondaryAudioPlayer?.play()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun isPlaying(): Boolean = nativePlayerPlaying

        @android.webkit.JavascriptInterface
        fun isActive(): Boolean = nativePlayerActive

        @android.webkit.JavascriptInterface
        fun pause() {
            runOnUiThread {
                nativePlayer?.pause()
                if (externalAudioActive) secondaryAudioPlayer?.pause()
            }
        }

        @android.webkit.JavascriptInterface
        fun resume() {
            runOnUiThread {
                nativePlayer?.play()
                if (externalAudioActive) secondaryAudioPlayer?.play()
            }
        }

        @android.webkit.JavascriptInterface
        fun setMuted(muted: Boolean) {
            runOnUiThread {
                nativeVideoMuted = muted
                nativePlayer?.volume = if (externalAudioActive || muted) 0f else 1f
            }
        }

        @android.webkit.JavascriptInterface
        fun seekBy(offsetMs: Long) {
            runOnUiThread {
                val player = nativePlayer ?: return@runOnUiThread
                if (!player.isCurrentMediaItemSeekable) return@runOnUiThread
                val target = (player.currentPosition + offsetMs).coerceAtLeast(0L)
                val duration = player.duration
                player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                if (externalAudioActive) {
                    secondaryAudioPlayer?.seekTo(target)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun setVideoQuality(maxHeight: Int) {
            runOnUiThread {
                val player = nativePlayer ?: return@runOnUiThread
                val height = maxHeight.coerceIn(360, 1080)
                val width = (height * 16f / 9f).roundToInt()
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(width, height)
                    .build()
            }
        }

        @android.webkit.JavascriptInterface
        fun setSubtitlesEnabled(enabled: Boolean) {
            runOnUiThread {
                val player = nativePlayer ?: return@runOnUiThread
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                    .build()
            }
        }

        @android.webkit.JavascriptInterface
        fun attachExternalAudio(url: String) { runOnUiThread { attachExternalAudioInternal(url) } }

        @android.webkit.JavascriptInterface
        fun detachExternalAudio() { runOnUiThread { detachExternalAudioInternal(false) } }

        @android.webkit.JavascriptInterface
        fun setExternalAudioSyncOffset(offsetMs: Int) {
            runOnUiThread { setExternalAudioSyncOffsetInternal(offsetMs.toLong()) }
        }

        @android.webkit.JavascriptInterface
        fun setExternalAudioVolume(volume: Float) {
            runOnUiThread {
                externalAudioVolume = volume.coerceIn(0f, 1f)
                secondaryAudioPlayer?.volume = externalAudioVolume
            }
        }

        @android.webkit.JavascriptInterface
        fun pauseExternalAudio() { runOnUiThread { secondaryAudioPlayer?.pause() } }

        @android.webkit.JavascriptInterface
        fun resumeExternalAudio() {
            runOnUiThread { if (externalAudioActive) secondaryAudioPlayer?.play() }
        }

        @android.webkit.JavascriptInterface
        fun isExternalAudioActive(): Boolean = externalAudioActive

        @android.webkit.JavascriptInterface
        fun resize(left: Float, top: Float, width: Float, height: Float) {
            runOnUiThread {
                val view = nativePlayerView ?: return@runOnUiThread
                val density = resources.displayMetrics.density
                val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@runOnUiThread
                params.width = (width * density).roundToInt().coerceAtLeast(1)
                params.height = (height * density).roundToInt().coerceAtLeast(1)
                params.leftMargin = (left * density).roundToInt()
                params.topMargin = (top * density).roundToInt()
                view.layoutParams = params
            }
        }
    }

    private fun attachExternalAudioInternal(rawUrl: String) {
        val url = rawUrl.trim()
        try {
            val uri = Uri.parse(url)
            require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                "رابط مصدر الصوت الخارجي غير صالح"
            }
            val currentVideoUrl = nativePlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
            require(currentVideoUrl.isNullOrBlank() || !currentVideoUrl.equals(url, ignoreCase = true)) {
                "مصدر الصوت يجب أن يكون قناة مستقلة عن مصدر الفيديو"
            }
        } catch (error: Exception) {
            detachExternalAudioInternal(false)
            val message = JSONObject.quote(error.message ?: "رابط الصوت الخارجي غير صالح")
            webView?.evaluateJavascript("window.onExternalAudioError && window.onExternalAudioError($message)", null)
            return
        }
        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
        externalAudioRetryRunnable = null
        externalAudioGeneration++
        externalAudioRetryAttempt = 0
        externalAudioSourceUrl = url
        val generation = externalAudioGeneration
        if (!externalAudioActive) {
            nativePlayer?.let { primary ->
                nativeVideoVolumeBeforeExternalAudio = primary.volume
                primary.volume = 0f
            }
        }
        externalAudioActive = true
        webView?.evaluateJavascript("window.onExternalAudioBuffering && window.onExternalAudioBuffering()", null)
        startExternalAudioAttempt(generation)
    }

    private fun startExternalAudioAttempt(generation: Long) {
        if (generation != externalAudioGeneration || !externalAudioActive) return
        releaseSecondaryAudio()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 30000, 1500, 5000)
            .build()
        val audioPlayer = ExoPlayer.Builder(this).setLoadControl(loadControl).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            volume = externalAudioVolume
        }
        secondaryAudioPlayer = audioPlayer
        audioPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != externalAudioGeneration || secondaryAudioPlayer !== audioPlayer) return
                when (state) {
                    Player.STATE_BUFFERING -> webView?.evaluateJavascript(
                        "window.onExternalAudioBuffering && window.onExternalAudioBuffering()", null)
                    Player.STATE_READY -> {
                        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
                        externalAudioRetryRunnable = null
                        externalAudioRetryAttempt = 0
                        applyExternalAudioSyncOffset()
                        webView?.evaluateJavascript(
                            "window.onExternalAudioPlaying && window.onExternalAudioPlaying()", null)
                    }
                    Player.STATE_ENDED -> scheduleExternalAudioRetry(generation)
                    Player.STATE_IDLE -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (generation != externalAudioGeneration || secondaryAudioPlayer !== audioPlayer) return
                android.util.Log.w("SONIXExternalAudio", "External IPTV audio failed: " + error.errorCodeName, error)
                scheduleExternalAudioRetry(generation)
            }
        })
        try {
            audioPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(externalAudioSourceUrl)))
            audioPlayer.prepare()
            audioPlayer.play()
        } catch (error: Exception) {
            android.util.Log.w("SONIXExternalAudio", "Could not start secondary audio player", error)
            scheduleExternalAudioRetry(generation)
        }
    }

    private fun scheduleExternalAudioRetry(generation: Long) {
        if (generation != externalAudioGeneration || !externalAudioActive) return
        releaseSecondaryAudio()
        externalAudioRetryAttempt = (externalAudioRetryAttempt + 1).coerceAtMost(6)
        val delayMs = externalAudioRetryDelayMs(externalAudioRetryAttempt)
        webView?.evaluateJavascript("window.onExternalAudioBuffering && window.onExternalAudioBuffering()", null)
        val retry = Runnable {
            externalAudioRetryRunnable = null
            if (generation == externalAudioGeneration && externalAudioActive) startExternalAudioAttempt(generation)
        }
        externalAudioRetryRunnable = retry
        externalAudioHandler.postDelayed(retry, delayMs)
    }

    private fun setExternalAudioSyncOffsetInternal(offsetMs: Long) {
        externalAudioSyncOffsetMs = offsetMs.coerceIn(-10000L, 10000L)
        applyExternalAudioSyncOffset()
    }

    private fun applyExternalAudioSyncOffset() {
        val player = secondaryAudioPlayer ?: return
        val offset = externalAudioSyncOffsetMs
        try {
            if (offset > 0L) {
                val position = player.currentPosition
                if (position > offset) player.seekTo(position - offset)
            } else if (offset < 0L) {
                player.seekTo(player.currentPosition + kotlin.math.abs(offset))
            }
        } catch (_: Exception) {
            // Some live manifests do not expose a seekable window.
        }
    }

    private fun detachExternalAudioInternal(notifyWeb: Boolean) {
        externalAudioGeneration++
        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
        externalAudioRetryRunnable = null
        externalAudioRetryAttempt = 0
        externalAudioActive = false
        externalAudioSourceUrl = ""
        externalAudioSyncOffsetMs = 0L
        releaseSecondaryAudio()
        nativePlayer?.volume = if (nativeVideoMuted) 0f else nativeVideoVolumeBeforeExternalAudio
        if (notifyWeb) webView?.evaluateJavascript(
            "window.onExternalAudioStopped && window.onExternalAudioStopped()", null)
    }

    private fun releaseSecondaryAudio() {
        secondaryAudioPlayer?.run { stop(); release() }
        secondaryAudioPlayer = null
    }

    private fun stopNativePlayer() {
        val player = nativePlayer
        val textureView = nativePlayerView
        if (player != null && textureView != null) {
            try { player.clearVideoTextureView(textureView) } catch (_: Exception) { }
        }
        textureView?.let { rootContainer?.removeView(it) }
        nativePlayerView = null
        player?.release()
        nativePlayer = null
        nativePlayerActive = false
        nativePlayerPlaying = false
        webView?.evaluateJavascript(
            "window.onNativePlaybackStateChanged && window.onNativePlaybackStateChanged(false)", null)
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
        if (externalAudioActive) secondaryAudioPlayer?.play()
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
        secondaryAudioPlayer?.pause()
        webView?.onPause()
    }

    override fun onDestroy() {
        detachExternalAudioInternal(false)
        stopNativePlayer()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}


/** Native equivalent of the SONIX screen gradients, visible behind the transparent WebView. */
private class SonixBackgroundDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val area = bounds
        val width = area.width().toFloat().coerceAtLeast(1f)
        val height = area.height().toFloat().coerceAtLeast(1f)
        paint.shader = LinearGradient(
            area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(),
            Color.rgb(11, 17, 24), Color.rgb(26, 36, 48), Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradient(
            area.left + width * 0.85f, area.top + height * 0.15f,
            maxOf(width, height) * 0.48f,
            intArrayOf(Color.argb(46, 184, 115, 51), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradient(
            area.left + width * 0.15f, area.top + height * 0.85f,
            maxOf(width, height) * 0.48f,
            intArrayOf(Color.argb(56, 31, 111, 107), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}


internal fun externalAudioRetryDelayMs(attempt: Int): Long {
    val delays = longArrayOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
    return delays[(attempt.coerceAtLeast(1) - 1).coerceAtMost(delays.lastIndex)]
}
