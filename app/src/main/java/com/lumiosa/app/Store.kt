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

data class HistItem(val uri: String, val name: String, val page: Int)

/** Recent files + last page per file, kept in SharedPreferences. */
class HistoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun load(): List<HistItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HistItem(o.getString("u"), o.getString("n"), o.optInt("p", 0))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<HistItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("p", it.page)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun touch(item: HistItem): List<HistItem> {
        val list = (listOf(item) + load().filter { it.uri != item.uri }).take(12)
        save(list)
        return list
    }

    fun setPage(uri: String, page: Int) {
        save(load().map { if (it.uri == uri) it.copy(page = page) else it })
    }

    fun remove(uri: String): List<HistItem> {
        val list = load().filter { it.uri != uri }
        save(list)
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
