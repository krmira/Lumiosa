package com.lumiosa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Thin wrapper over Android's PdfRenderer. Nothing is parsed up front, so a PDF
 * opens instantly; only the page on screen (and its neighbours) get rendered.
 * PdfRenderer allows one open page at a time, hence the mutex.
 */
class PdfEngine private constructor(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor
) {
    val pageCount: Int = renderer.pageCount

    private val mutex = Mutex()
    @Volatile private var closed = false
    private var margin = 0f // fraction trimmed from each side, same for every page

    /** Text-reflow companion; only created for reader sessions (not history thumbnails). */
    var reflow: ReflowEngine? = null

    private val cache = object : LruCache<Int, Bitmap>(cacheKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Any already-rendered version of this page (may be lower resolution). */
    fun cached(index: Int): Bitmap? = cache.get(index)

    /**
     * Page image, [targetW] px wide after trimming margins. A cached copy is
     * reused unless the caller now needs noticeably more resolution (zoom).
     */
    suspend fun page(index: Int, targetW: Int): Bitmap? {
        if (closed || index < 0 || index >= pageCount) return null
        cache.get(index)?.let { if (it.width >= targetW * 0.9f) return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock null
                val have = cache.get(index)
                if (have != null && have.width >= targetW * 0.9f) return@withLock have
                try {
                    renderCropped(index, targetW)?.also { cache.put(index, it) }
                } catch (e: Exception) {
                    have
                } catch (e: OutOfMemoryError) {
                    cache.evictAll()
                    null
                }
            }
        }
    }

    /** First page, uncropped, for history thumbnails. */
    suspend fun thumb(width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || pageCount == 0) null else try {
                renderRaw(0, width)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        reflow?.close()
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                try {
                    renderer.close()
                    pfd.close()
                } catch (e: Exception) {
                }
                cache.evictAll()
            }
        }
    }

    private fun renderCropped(index: Int, targetW: Int): Bitmap? {
        val keep = 1f - 2f * margin
        val fullW = (targetW / keep).toInt().coerceIn(200, 3400)
        val full = renderRaw(index, fullW) ?: return null
        val x = (full.width * margin).toInt()
        if (x <= 0) return full
        val out = Bitmap.createBitmap(full, x, 0, full.width - 2 * x, full.height)
        if (out !== full) full.recycle()
        return out
    }

    private fun renderRaw(index: Int, w: Int): Bitmap? {
        val page = renderer.openPage(index)
        try {
            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    /** Side margin (fraction of width) of one page, or -1 if the page is blank. */
    private fun pageMargin(index: Int): Float {
        val bmp = renderRaw(index, 360) ?: return -1f
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var minX = w
        var maxX = -1
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                if (Color.red(p) < 225 || Color.green(p) < 225 || Color.blue(p) < 225) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
                x += 2
            }
            y += 2
        }
        bmp.recycle()
        if (maxX <= minX) return -1f
        return minOf(minX.toFloat() / w, (w - 1 - maxX).toFloat() / w)
    }

    /** Samples a few pages across the book and trims the typical white margin. */
    private fun detectMargin() {
        try {
            val n = pageCount
            if (n == 0) return
            val picks = listOf(minOf(2, n - 1), minOf(6, n - 1), n / 4, n / 2, (3 * n) / 4)
                .filter { it in 0 until n }.distinct()
            val found = picks.map { pageMargin(it) }.filter { it >= 0f }.sorted()
            if (found.isNotEmpty()) {
                val median = found[found.size / 2]
                margin = (median - 0.015f).coerceIn(0f, 0.2f)
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        private fun cacheKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()

        /** Blocking: call from a background thread. */
        fun open(ctx: Context, uri: Uri, detectMargins: Boolean = true): PdfEngine {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file")
            val engine = try {
                PdfEngine(PdfRenderer(pfd), pfd)
            } catch (e: SecurityException) {
                pfd.close()
                throw e
            } catch (e: IOException) {
                // Source is not seekable: copy it to cache and open that instead.
                pfd.close()
                val tmp = File.createTempFile("open", ".pdf", ctx.cacheDir)
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read file")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                val p2 = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                tmp.delete()
                try {
                    PdfEngine(PdfRenderer(p2), p2)
                } catch (e2: Exception) {
                    p2.close()
                    throw e2
                }
            }
            if (detectMargins) {
                engine.detectMargin()
                // Start getting reflow ready now so it is instant when switched on.
                engine.reflow = ReflowEngine(ctx.applicationContext, uri).also { it.warmUp() }
            }
            return engine
        }
    }
}
