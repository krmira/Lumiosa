package com.lumiosa.app

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Reflow mode: pulls the text out of one page at a time and turns it into
 * paragraphs the UI can wrap to any width.
 *
 * Why it is quick:
 *  - the text library starts loading in the background as soon as the PDF is
 *    opened (warmUp), while page view is already usable;
 *  - only single pages are extracted, never the whole book;
 *  - the current page plus a few pages ahead/behind are prefetched, and every
 *    result is cached.
 * All work runs on one low-priority thread, so it never blocks scrolling.
 */
class ReflowEngine(private val ctx: Context, private val uri: Uri) {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumiosa-reflow").also { it.priority = Thread.MIN_PRIORITY + 1 }
    }
    private val dispatcher = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var closed = false
    private var doc: PDDocument? = null
    private var tmp: File? = null
    private var failed = false
    private var pages = 0

    // Paragraph lists per page index. Entry count bounds memory; text is tiny.
    private val cache = LruCache<Int, List<String>>(60)
    private var prefetchJob: Job? = null

    /** Already-extracted paragraphs for a page, or null if not ready yet. */
    fun cached(index: Int): List<String>? = cache.get(index)

    /** Start loading the text library and the file now, without waiting for a page request. */
    fun warmUp() {
        scope.launch { try { ensureDoc() } catch (e: Exception) { } }
    }

    /** Paragraphs of one page (empty list = no selectable text / unreadable). */
    suspend fun page(index: Int): List<String> = withContext(dispatcher) { load(index) }

    /** Extract the pages around [center] in the background, nearest first. */
    fun prefetch(center: Int) {
        if (closed) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (i in intArrayOf(center, center + 1, center - 1, center + 2, center + 3)) {
                if (!isActive) return@launch
                if (i >= 0) load(i)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        // Queued on the same thread, so it runs after any extraction in progress.
        exec.execute {
            try { doc?.close() } catch (e: Exception) { }
            doc = null
            tmp?.delete()
        }
        exec.shutdown()
    }

    // ---- everything below runs on the single reflow thread ----

    private fun load(index: Int): List<String> {
        cache.get(index)?.let { return it }
        if (closed) return emptyList()
        val d = try { ensureDoc() } catch (e: Exception) { null } ?: return emptyList()
        if (index < 0 || index >= pages) return emptyList()
        val result = try {
            val s = PDFTextStripper()
            s.setStartPage(index + 1)
            s.setEndPage(index + 1)
            s.setLineSeparator("\n")
            toParagraphs(s.getText(d))
        } catch (e: Exception) {
            emptyList()
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            emptyList()
        }
        if (!closed) cache.put(index, result)
        return result
    }

    private fun ensureDoc(): PDDocument? {
        doc?.let { return it }
        if (failed || closed) return null
        try {
            PDFBoxResourceLoader.init(ctx)
            cleanOldTemps()
            val f = File.createTempFile("reflow", ".pdf", ctx.cacheDir)
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read file")
            input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            tmp = f
            // File-backed load: pages are parsed lazily, so big books don't fill memory.
            val d = PDDocument.load(f)
            pages = d.numberOfPages
            doc = d
            return d
        } catch (e: Exception) {
            failed = true
            tmp?.delete()
            return null
        } catch (e: OutOfMemoryError) {
            failed = true
            tmp?.delete()
            return null
        }
    }

    private fun cleanOldTemps() {
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        ctx.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("reflow") && it.name.endsWith(".pdf") && it.lastModified() < cutoff) it.delete()
        }
    }

    /**
     * PDF text comes out as one string per visual line. Join lines back into
     * paragraphs: a line that ends well short of full width, or ends a sentence
     * near the margin, starts a new paragraph; words split by a hyphen at the
     * line end are glued back together.
     */
    private fun toParagraphs(raw: String): List<String> {
        val lines = raw.replace("\r", "").split("\n").map { it.trim() }
        val nonBlank = lines.filter { it.isNotEmpty() }.toMutableList()
        if (nonBlank.isEmpty()) return emptyList()
        // Drop a bare page number at the very top or bottom.
        fun isNum(s: String) = s.length <= 4 && s.all { it.isDigit() }
        if (nonBlank.size > 1 && isNum(nonBlank.last())) nonBlank.removeAt(nonBlank.lastIndex)
        if (nonBlank.size > 1 && isNum(nonBlank.first())) nonBlank.removeAt(0)
        val skip = HashSet<String>()
        val maxLen = nonBlank.maxOf { it.length }.coerceAtLeast(1)

        val out = ArrayList<String>()
        val cur = StringBuilder()
        var prev = ""
        fun flush() {
            if (cur.isNotEmpty()) out.add(cur.toString().replace(Regex("\\s+"), " ").trim())
            cur.clear()
            prev = ""
        }
        for (line in lines) {
            if (line.isEmpty()) { flush(); continue }
            if (line.length <= 4 && isNum(line) && line !in nonBlank) continue
            if (cur.isEmpty()) {
                cur.append(line)
            } else {
                val first = line.first()
                val endsSentence = prev.last() in ".!?:\u201D\"\u2019)"
                val short = prev.length < maxLen * 0.6
                val nearEnd = prev.length < maxLen * 0.85
                if (short || (endsSentence && nearEnd && (first.isUpperCase() || first.isDigit()))) {
                    flush()
                    cur.append(line)
                } else if (prev.length > 1 && prev.last() == '-' && prev[prev.length - 2].isLetter() && first.isLowerCase()) {
                    cur.setLength(cur.length - 1) // glue "hyphen-" + "ated" -> "hyphenated"
                    cur.append(line)
                } else {
                    cur.append(' ').append(line)
                }
            }
            prev = line
        }
        flush()
        return out.filter { it.isNotBlank() }
    }
}
