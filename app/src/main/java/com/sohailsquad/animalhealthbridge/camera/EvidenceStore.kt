package com.sohailsquad.animalhealthbridge.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EvidenceStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "evidence")
    suspend fun validate(uri: Uri, mime: String): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "file")
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(file.parentFile == root.canonicalFile && file.isFile && file.length() in 1..(50L * 1024 * 1024)) {
            "Attachment is missing or exceeds 50 MB."
        }
        when (mime) {
            "image/jpeg" -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outMimeType == "image/jpeg") {
                    "Photo is unreadable."
                }
            }
            "video/mp4" -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    require(duration != null && duration in 1..31_000) { "Video must be at most 30 seconds." }
                    require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                } finally { retriever.release() }
            }
            else -> error("Unsupported attachment type.")
        }
        uri.toString()
    }
}
