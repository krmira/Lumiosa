package com.lumiosa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistItem(val uri: String, val name: String, val page: Int, val scroll: Int = 0)

/** Recent files + last page per file, kept in SharedPreferences. */
class HistoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<HistItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistItem(o.getString("u"), o.getString("n"), o.optInt("p", 0), o.optInt("s", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<HistItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("p", it.page).put("s", it.scroll)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    // Page view: 1x = whole page width fits the screen (no sideways scrolling).
    fun zoom(): Float = prefs.getFloat("pzoom", 1f)

    fun setZoom(z: Float) {
        prefs.edit().putFloat("pzoom", z).apply()
    }

    // Reflow text size multiplier: 2x = 18sp body text.
    fun rzoom(): Float = prefs.getFloat("rzoom", 2f)

    fun setRzoom(z: Float) {
        prefs.edit().putFloat("rzoom", z).apply()
    }

    fun reflow(): Boolean = prefs.getBoolean("reflow2", true)

    fun setReflow(on: Boolean) {
        prefs.edit().putBoolean("reflow2", on).apply()
    }

    fun touch(item: HistItem): List<HistItem> {
        val list = (listOf(item) + load().filter { it.uri != item.uri }).take(12)
        save(list)
        return list
    }

    /** Remember where the reader is: page, plus how far down that page (reflow). */
    fun setPage(uri: String, page: Int, scroll: Int = 0) {
        save(load().map { if (it.uri == uri) it.copy(page = page, scroll = scroll) else it })
    }

    // ---- bookmarks: page numbers (0-based) per PDF ----
    private fun bmKey(uri: String) = "bm:$uri"

    fun bookmarks(uri: String): List<Int> =
        (prefs.getString(bmKey(uri), "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.sorted()

    /** Adds the page if missing, removes it if present; returns the new sorted list. */
    fun toggleBookmark(uri: String, page: Int): List<Int> {
        val cur = bookmarks(uri)
        val next = (if (page in cur) cur - page else cur + page).sorted()
        prefs.edit().putString(bmKey(uri), next.joinToString(",")).apply()
        return next
    }

    fun remove(uri: String): List<HistItem> {
        val list = load().filter { it.uri != uri }
        save(list)
        prefs.edit().remove(bmKey(uri)).apply()
        return list
    }
}

/** First-page thumbnails, cached as small JPEGs. */
object Thumbs {
    suspend fun get(ctx: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
            val f = File(dir, Integer.toHexString(uri.hashCode()) + ".jpg")
            if (f.exists()) return@withContext BitmapFactory.decodeFile(f.path)
            val engine = PdfEngine.open(ctx, Uri.parse(uri), detectMargins = false)
            val bmp = engine.thumb(300)
            engine.close()
            if (bmp != null) f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
