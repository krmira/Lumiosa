package com.lumiosa.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial: Uri? = intent?.data
        setContent { App(initial) }
    }
}

private fun displayName(ctx: Context, uri: Uri): String {
    var name: String? = null
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
    } catch (e: Exception) {
    }
    val n = name ?: uri.lastPathSegment ?: "PDF"
    return n.removeSuffix(".pdf").removeSuffix(".PDF")
}

@Composable
fun App(initialUri: Uri?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { HistoryStore(ctx) }

    var history by remember { mutableStateOf(store.load()) }
    var engine by remember { mutableStateOf<PdfEngine?>(null) }
    var doc by remember { mutableStateOf<HistItem?>(null) }
    var night by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    fun open(uri: Uri) {
        scope.launch {
            message = ""
            try {
                val e = withContext(Dispatchers.IO) { PdfEngine.open(ctx, uri) }
                val key = uri.toString()
                val saved = store.load().firstOrNull { it.uri == key }
                val item = HistItem(key, saved?.name ?: displayName(ctx, uri), saved?.page ?: 0)
                engine?.close()
                engine = e
                doc = item
                history = store.touch(item)
            } catch (e: SecurityException) {
                message = "This PDF is password protected"
            } catch (e: Exception) {
                message = "Could not open this PDF"
                history = store.remove(uri.toString())
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            open(uri)
        }
    }

    LaunchedEffect(Unit) { initialUri?.let { open(it) } }

    BackHandler(enabled = doc != null) {
        engine?.close()
        engine = null
        doc = null
    }

    val eng = engine
    val cur = doc
    if (eng != null && cur != null) {
        ReaderScreen(
            engine = eng,
            startPage = cur.page,
            night = night,
            onNight = { night = !night },
            onBack = {
                eng.close()
                engine = null
                doc = null
            },
            onPage = { store.setPage(cur.uri, it) }
        )
    } else {
        MenuScreen(
            history = history,
            message = message,
            onPick = { picker.launch(arrayOf("application/pdf")) },
            onOpen = { open(Uri.parse(it.uri)) }
        )
    }
}

@Composable
private fun MenuScreen(
    history: List<HistItem>,
    message: String,
    onPick: () -> Unit,
    onOpen: (HistItem) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Pal.Dark).padding(20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoTile(null)
            Pill("OPEN PDF", onPick)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    "The more comfortable way to read",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = TextStyle(
                        color = Pal.Ink,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center
                    )
                )
                if (message.isNotEmpty()) {
                    BasicText(
                        message,
                        modifier = Modifier.padding(top = 20.dp),
                        style = TextStyle(color = Pal.Leaf, fontSize = 15.sp, textAlign = TextAlign.Center)
                    )
                }
            }
        }
        BasicText(
            "History",
            modifier = Modifier.padding(bottom = 10.dp),
            style = TextStyle(color = Pal.Dim, fontSize = 15.sp)
        )
        if (history.isEmpty()) {
            BasicText(
                "Your recent PDFs will appear here",
                modifier = Modifier.padding(bottom = 24.dp),
                style = TextStyle(color = Pal.Mid, fontSize = 14.sp)
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { it.uri }) { HistCard(it, onOpen) }
            }
        }
    }
}

@Composable
private fun HistCard(item: HistItem, onOpen: (HistItem) -> Unit) {
    val ctx = LocalContext.current
    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri) { thumb = Thumbs.get(ctx, item.uri) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(104.dp).clickable { onOpen(item) }) {
        Box(
            Modifier.size(width = 104.dp, height = 140.dp)
                .clip(shape)
                .background(Pal.Deep)
                .border(1.5.dp, Pal.Mid, shape)
        ) {
            val t = thumb
            if (t != null) {
                val img = remember(t) { t.asImageBitmap() }
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    colorFilter = NightFilter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        BasicText(
            item.name,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Pal.Dim, fontSize = 12.sp)
        )
    }
}

@Composable
private fun ReaderScreen(
    engine: PdfEngine,
    startPage: Int,
    night: Boolean,
    onNight: () -> Unit,
    onBack: () -> Unit,
    onPage: (Int) -> Unit
) {
    val pager = rememberPagerState(
        initialPage = startPage.coerceIn(0, maxOf(0, engine.pageCount - 1))
    ) { engine.pageCount }
    LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }

    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxSize().background(Pal.Dark).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoTile(onBack)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    if (night) "\u25D0" else "\u25CB",
                    modifier = Modifier.clickable(onClick = onNight).padding(10.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 22.sp)
                )
                Spacer(Modifier.width(6.dp))
                Pill("${pager.currentPage + 1} / ${engine.pageCount}", null)
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                .clip(shape)
                .background(if (night) Pal.Deep else Color.White)
                .border(2.dp, Pal.Mid, shape)
                .padding(2.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wPx = constraints.maxWidth
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { i -> PageView(engine, i, wPx, night) }
            }
        }
    }
}

@Composable
private fun PageView(engine: PdfEngine, index: Int, widthPx: Int, night: Boolean) {
    var bmp by remember(engine, index, widthPx) { mutableStateOf(engine.cached(index, widthPx)) }
    LaunchedEffect(engine, index, widthPx) {
        if (bmp == null) bmp = engine.page(index, widthPx)
    }
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val b = bmp
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                colorFilter = if (night) NightFilter else null,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
