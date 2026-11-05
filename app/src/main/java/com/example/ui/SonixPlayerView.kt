package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.model.Channel
import com.example.model.DualAudioState
import com.example.ui.theme.*

@Composable
fun SonixPlayerView(
    channel: Channel,
    dualAudioState: DualAudioState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenEpg: () -> Unit,
    onOpenDualAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.localizedMessage ?: "تعذر تشغيل البث"
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackError = null
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(channel.id, channel.streamUrl) {
        playbackError = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (channel.streamUrl.isNotBlank()) {
            exoPlayer.setMediaItem(MediaItem.fromUri(channel.streamUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            playbackError = "رابط البث غير متوفر"
        }
    }

    LaunchedEffect(isMuted, dualAudioState.isActive) {
        exoPlayer.volume = if (isMuted || dualAudioState.isActive) 0f else 1f
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SonixBgScreen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, SonixBorderPanel, RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext: Context ->
                    PlayerView(viewContext).apply {
                        useController = true
                        controllerAutoShow = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepContentOnPlayerReset = true
                        this.player = exoPlayer
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { view -> if (view.player !== exoPlayer) view.player = exoPlayer }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(SonixDangerRed))
                Text("مباشر", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            if (dualAudioState.isActive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xE61F6F6B))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = SonixAccentGold, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("الصوت الخارجي نشط", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            playbackError?.let { message ->
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(message, color = Color.White, fontSize = 12.sp)
                    Button(onClick = {
                        playbackError = null
                        player.prepare()
                        player.playWhenReady = true
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("إعادة المحاولة")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = SonixTextWhite, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(channel.categoryName, color = SonixTextMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "إزالة من المفضلة" else "إضافة للمفضلة",
                    tint = if (isFavorite) SonixDangerRed else SonixTextSilver
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenEpg, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("دليل البرامج", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { isMuted = !isMuted }, modifier = Modifier.weight(1f)) {
                Text(if (isMuted) "إلغاء الكتم" else "كتم الصوت", fontSize = 11.sp)
            }
            Button(
                onClick = onOpenDualAudio,
                modifier = Modifier.weight(1.2f),
                colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("الصوت الخارجي", fontSize = 11.sp)
            }
        }
    }
}
