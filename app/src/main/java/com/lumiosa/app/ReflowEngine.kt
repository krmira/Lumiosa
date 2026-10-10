package com.lumiosa.app

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Reflow mode: turns the PDF into plain paragraphs the UI can wrap to any width.
 *
 * How it stays fast:
 *  - The first time a PDF is opened, the text library loads in the background.
 *    The page you are on (and the few next to it) are extracted first, then the
 *    rest of the book is converted behind the scenes.
 *  - When the whole book is done it is saved as a plain-text file on the phone.
 *    The next time you open that PDF the text loads straight from that file:
 *    no PDF parsing, no library start-up, instant.
 * All work runs on one low-priority thread, so scrolling never stutters.
 */
/** One paragraph. heading == 0 for body text, otherwise the title's font size relative to body text. */
data class Para(val text: String, val heading: Float = 0f)

/** PDFTextStripper that also records the average font size of every visual line. */
private class SizedStripper : PDFTextStripper() {
    class Line(val text: String, val size: Float)

    val lines = ArrayList<Line>()
    private val cur = StringBuilder()
    private var sizeSum = 0f
    private var glyphs = 0

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        cur.append(text)
        for (p in textPositions) { sizeSum += p.fontSizeInPt; glyphs++ }
    }

    override fun writeWordSeparator() {
        cur.append(' ')
    }

    override fun writeLineSeparator() {
        flushLine()
    }

    fun flushLine() {
        val t = cur.toString().trim()
        if (t.isNotEmpty()) lines.add(Line(t, if (glyphs > 0) sizeSum / glyphs else 0f))
        cur.setLength(0)
        sizeSum = 0f
        glyphs = 0
    }
}

class ReflowEngine(private val ctx: Context, private val uri: Uri) {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumiosa-reflow").also { it.priority = Thread.MIN_PRIORITY + 1 }
    }
    private val dispatcher = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var closed = false
    @Volatile private var complete = false // every page is in memory (and saved to disk)
    private var doc: PDDocument? = null
    private var tmp: File? = null
    private var failed = false
    @Volatile private var pages = 0
    @Volatile private var done = 0

    private val pageText = ConcurrentHashMap<Int, List<Para>>()

    /** Typical body-text size in PDF points and the page width in points; the UI turns these into screen sizes. */
    @Volatile var bodyPt = 0f
        private set
    @Volatile var pageWidthPt = 612f
        private set
    private var bodyFrozen = false
    private val sizeChars = HashMap<Float, Int>()
    private var prefetchJob: Job? = null

    /** Already-extracted paragraphs for a page, or null if not ready yet. */
    fun cached(index: Int): List<Para>? = pageText[index]

    /** 0..1 progress of converting the whole book (1 when finished or loaded from disk). */
    val progress: Float get() = if (complete) 1f else if (pages > 0) done / pages.toFloat() else 0f

    /** Load the saved text if there is any, otherwise start converting the whole book. */
    fun warmUp() {
        scope.launch {
            try {
                if (loadFromDisk()) return@launch
                convertAll()
            } catch (e: Exception) {
            }
        }
    }

    /** Paragraphs of one page (empty list = no selectable text / unreadable). */
    suspend fun page(index: Int): List<Para> = withContext(dispatcher) { load(index) }

    /** Extract the pages around [center] first, nearest first (no-op once everything is loaded). */
    fun prefetch(center: Int) {
        if (closed || complete) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (i in intArrayOf(center, center + 1, center - 1, center + 2, center + 3)) {
                if (!isActive) return@launch
                if (i >= 0) load(i)
                yield()
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

    private suspend fun convertAll() {
        val d = ensureDoc() ?: return
        val n = pages
        for (i in 0 until n) {
            if (closed) return
            load(i)
            done = i + 1
            yield() // let page requests from the screen jump the queue
        }
        if (closed) return
        saveToDisk(n)
        complete = true
        // Nothing more to read from the PDF: free its memory and the temp copy.
        try { d.close() } catch (e: Exception) { }
        doc = null
        tmp?.delete()
        tmp = null
    }

    private fun load(index: Int): List<Para> {
        pageText[index]?.let { return it }
        if (closed || complete) return emptyList()
        val d = try { ensureDoc() } catch (e: Exception) { null } ?: return emptyList()
        if (index < 0 || index >= pages) return emptyList()
        val result = try {
            val s = SizedStripper()
            s.setStartPage(index + 1)
            s.setEndPage(index + 1)
            s.getText(d)
            s.flushLine()
            toParagraphs(s.lines)
        } catch (e: Exception) {
            emptyList()
        } catch (e: OutOfMemoryError) {
            emptyList()
        }
        if (!closed) pageText[index] = result
        return result
    }

    private fun ensureDoc(): PDDocument? {
        doc?.let { return it }
        if (failed || closed || complete) return null
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
            try { pageWidthPt = d.getPage(0).mediaBox.width } catch (e: Exception) { }
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

    // ---- saved text: "LUMIOSA2<TAB>pages<TAB>bodyPt<TAB>pageWidthPt\n", then pages split by \f, paragraphs by \n;
    //      a title paragraph is written as \u0001<size ratio>\u0002<text> ----

    private fun cacheFile(): File? = try {
        val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        val dir = File(ctx.filesDir, "reflow").apply { mkdirs() }
        File(dir, Integer.toHexString(uri.toString().hashCode()) + "_" + size + ".txt")
    } catch (e: Exception) {
        null
    }

    private fun loadFromDisk(): Boolean {
        val f = cacheFile() ?: return false
        if (!f.exists()) return false
        try {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl < 0) return false
            val head = text.substring(0, nl).split('\t')
            if (head.size != 4 || head[0] != "LUMIOSA2") return false
            val n = head[1].toIntOrNull() ?: return false
            val bp = head[2].toFloatOrNull() ?: return false
            val pw = head[3].toFloatOrNull() ?: return false
            val parts = text.substring(nl + 1).split('\u000C')
            if (parts.size != n) return false
            parts.forEachIndexed { i, p ->
                pageText[i] = if (p.isEmpty()) emptyList() else p.split('\n').map { decodePara(it) }
            }
            bodyPt = bp
            pageWidthPt = pw
            bodyFrozen = true
            pages = n
            done = n
            complete = true
            f.setLastModified(System.currentTimeMillis())
            return true
        } catch (e: Exception) {
            pageText.clear()
            return false
        } catch (e: OutOfMemoryError) {
            pageText.clear()
            return false
        }
    }

    private fun encodePara(p: Para): String =
        if (p.heading > 0f) "\u0001" + p.heading + "\u0002" + p.text else p.text

    private fun decodePara(line: String): Para {
        if (line.startsWith("\u0001")) {
            val k = line.indexOf('\u0002')
            if (k > 0) return Para(line.substring(k + 1), line.substring(1, k).toFloatOrNull() ?: 1.3f)
        }
        return Para(line)
    }

    private fun saveToDisk(n: Int) {
        val f = cacheFile() ?: return
        try {
            val sb = StringBuilder()
            sb.append("LUMIOSA2\t").append(n).append('\t').append(bodyPt).append('\t').append(pageWidthPt).append('\n')
            for (i in 0 until n) {
                if (i > 0) sb.append('\u000C')
                sb.append((pageText[i] ?: emptyList()).joinToString("\n") { encodePara(it) })
            }
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(sb.toString())
            if (!part.renameTo(f)) part.delete()
            // Keep the 25 most recently used books; drop older saved text.
            f.parentFile?.listFiles { x -> x.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }?.drop(25)?.forEach { it.delete() }
        } catch (e: Exception) {
        }
    }

    /** Track which font size most of the text uses; frozen after enough text so sizes don't jump between pages. */
    private fun learnBodySize(lines: List<SizedStripper.Line>) {
        if (bodyFrozen) return
        for (l in lines) {
            val key = Math.round(l.size * 2f) / 2f
            sizeChars[key] = (sizeChars[key] ?: 0) + l.text.length
        }
        val total = sizeChars.values.sum()
        bodyPt = sizeChars.maxByOrNull { it.value }?.key ?: bodyPt
        if (total >= 600) bodyFrozen = true
    }

    /**
     * Lines (with font sizes) -> paragraphs.
     *  - Lines clearly bigger than body text are titles; neighbouring title lines of
     *    the same size become one title.
     *  - Body lines are joined: a line that ends well short of full width, or ends a
     *    sentence near the margin, starts a new paragraph; words split by a hyphen at
     *    the line end are glued back together.
     */
    private fun toParagraphs(raw: List<SizedStripper.Line>): List<Para> {
        val lines = raw.toMutableList()
        fun isNum(s: String) = s.length <= 4 && s.all { it.isDigit() }
        // Drop a bare page number at the very top or bottom.
        if (lines.size > 1 && isNum(lines.last().text)) lines.removeAt(lines.lastIndex)
        if (lines.size > 1 && isNum(lines.first().text)) lines.removeAt(0)
        if (lines.isEmpty()) return emptyList()
        learnBodySize(lines)
        val body = if (bodyPt > 0f) bodyPt else lines.maxByOrNull { it.text.length }!!.size
        fun ratio(l: SizedStripper.Line) = if (body > 0f) l.size / body else 1f
        fun isTitle(l: SizedStripper.Line) = ratio(l) > 1.18f && l.text.length <= 140
        val maxLen = lines.filter { !isTitle(it) }.maxOfOrNull { it.text.length }?.coerceAtLeast(1) ?: 1

        val out = ArrayList<Para>()
        val cur = StringBuilder()
        var prev = ""
        val title = StringBuilder()
        var titleRatio = 0f
        fun flushBody() {
            if (cur.isNotEmpty()) out.add(Para(cur.toString().replace(Regex("\\s+"), " ").trim()))
            cur.clear()
            prev = ""
        }
        fun flushTitle() {
            if (title.isNotEmpty()) out.add(Para(title.toString().replace(Regex("\\s+"), " ").trim(), titleRatio))
            title.setLength(0)
        }
        for (l in lines) {
            val line = l.text
            if (isTitle(l)) {
                flushBody()
                if (title.isNotEmpty() && Math.abs(titleRatio - ratio(l)) < 0.1f) title.append(' ').append(line)
                else { flushTitle(); title.append(line); titleRatio = (Math.round(ratio(l) * 10f) / 10f) }
                continue
            }
            flushTitle()
            if (cur.isEmpty()) {
                cur.append(line)
            } else {
                val first = line.first()
                val endsSentence = prev.last() in ".!?:\u201D\"\u2019)"
                val short = prev.length < maxLen * 0.6
                val nearEnd = prev.length < maxLen * 0.85
                if (short || (endsSentence && nearEnd && (first.isUpperCase() || first.isDigit()))) {
                    flushBody()
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
        flushBody()
        flushTitle()
        return out.filter { it.text.isNotBlank() }
    }
}
