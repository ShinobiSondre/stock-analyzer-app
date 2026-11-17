package com.shinobisondre.stockanalyzer.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Text out of a PDF (Nordnet's transaction export), on the phone. */
object Pdf {
    @Volatile private var ready = false

    suspend fun text(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        if (!ready) { PDFBoxResourceLoader.init(ctx.applicationContext); ready = true }
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        input.use { s ->
            PDDocument.load(s).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) }
        }
    }
}
