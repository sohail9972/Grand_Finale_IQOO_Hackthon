package com.sohailsquad.animalhealthbridge.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EvidencePreview(uri: String, mime: String,
    modifier: Modifier = Modifier.fillMaxWidth().height(180.dp), contentDescription: String = "Case photo") {
    var play by remember(uri) { mutableStateOf(false) }
    if (mime.startsWith("image/")) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(Uri.parse(uri).path,
                    BitmapFactory.Options().apply { inSampleSize = 8 }) }
                    .onFailure { Log.w("AhbCamera", "Preview unavailable", it) }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), contentDescription, modifier.clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop) }
            ?: Text("Photo saved; preview unavailable.", modifier = modifier,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
    } else {
        TextButton(onClick = { play = true }) { Text("Preview video") }
    }
    if (play) {
        val context = LocalContext.current
        var playbackError by remember(uri) { mutableStateOf<String?>(null) }
        val player = remember(uri) {
            runCatching { ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = "Video saved; this phone cannot preview it."
                        Log.w("AhbCamera", "Video playback failed", error)
                    }
                })
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            } }.onFailure { Log.w("AhbCamera", "Video preview unavailable", it) }.getOrNull()
        }
        DisposableEffect(player) { onDispose { player?.release() } }
        Dialog(onDismissRequest = { play = false }) {
            Surface {
                Column(Modifier.padding(16.dp)) {
                    playbackError?.let { Text(it) }
                    if (player != null) AndroidView(
                        factory = { PlayerView(it).apply { this.player = player } },
                        modifier = Modifier.fillMaxWidth().height(240.dp))
                    else Text("Video saved; preview unavailable.")
                    TextButton(onClick = { play = false }) { Text("Close") }
                }
            }
        }
    }
}
