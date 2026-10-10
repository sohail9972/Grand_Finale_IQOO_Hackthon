package com.sohailsquad.animalhealthbridge.data

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

class RecordExporter(context: Context) {
    private val context = context.applicationContext
    suspend fun createPdf(lines: List<String>, name: String): Uri? = withContext(Dispatchers.IO) {
        var file: File? = null
        try {
            require(name.matches(Regex("[a-zA-Z0-9_-]+\\.pdf")))
            val directory = File(context.cacheDir, "exports")
            require(directory.isDirectory || directory.mkdirs())
            file = File(directory, name)
            val document = PdfDocument()
            try {
                val paint = Paint().apply { textSize = 12f; isAntiAlias = true }
                var number = 1
                var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                var baseline = 40f
                for (line in lines.flatMap { it.lines() }) {
                    var remaining = line
                    do {
                        if (baseline > 800f) {
                            document.finishPage(page)
                            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                            baseline = 40f
                        }
                        val count = paint.breakText(remaining, true, 515f, null).coerceAtLeast(1).coerceAtMost(remaining.length)
                        page.canvas.drawText(remaining.take(count), 40f, baseline, paint)
                        remaining = remaining.drop(count)
                        baseline += 18f
                    } while (remaining.isNotEmpty())
                }
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            Log.i("AhbRecord", "PDF created: $name")
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        } catch (failure: CancellationException) { file?.delete(); throw failure }
        catch (failure: Throwable) {
            file?.delete()
            Log.w("AhbRecord", "PDF export unavailable", failure)
            null
        }
    }
    fun share(uri: Uri): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Record", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share local record").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i("AhbRecord", "User-initiated PDF share")
        true
    } catch (failure: Throwable) {
        Log.w("AhbRecord", "No sharing application available", failure)
        false
    }
}
