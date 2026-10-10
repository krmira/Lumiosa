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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    var zoom by remember { mutableStateOf(store.zoom()) }
    var rzoom by remember { mutableStateOf(store.rzoom()) }
    var reflow by remember { mutableStateOf(store.reflow()) }
    var message by remember { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(listOf<Int>()) }

    fun open(uri: Uri) {
        scope.launch {
            message = ""
            try {
                val e = withContext(Dispatchers.IO) { PdfEngine.open(ctx, uri) }
                val key = uri.toString()
                val saved = store.load().firstOrNull { it.uri == key }
                val item = HistItem(key, saved?.name ?: displayName(ctx, uri), saved?.page ?: 0, saved?.scroll ?: 0)
                engine?.close()
                engine = e
                doc = item
                bookmarks = store.bookmarks(key)
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
    LaunchedEffect(zoom) {
        delay(500)
        store.setZoom(zoom)
    }
    LaunchedEffect(rzoom) {
        delay(500)
        store.setRzoom(rzoom)
    }

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
            startScroll = cur.scroll,
            bookmarks = bookmarks,
            onBookmark = { bookmarks = store.toggleBookmark(cur.uri, it) },
            night = night,
            zoom = if (reflow) rzoom else zoom,
            onZoom = { if (reflow) rzoom = it.coerceIn(1f, 5f) else zoom = it.coerceIn(1f, 5f) },
            onNight = { night = !night },
            reflow = reflow,
            onReflow = {
                reflow = !reflow
                store.setReflow(reflow)
            },
            onBack = {
                eng.close()
                engine = null
                doc = null
            },
            onPage = { p, sc -> store.setPage(cur.uri, p, sc) }
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
    startScroll: Int,
    bookmarks: List<Int>,
    onBookmark: (Int) -> Unit,
    night: Boolean,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onNight: () -> Unit,
    reflow: Boolean,
    onReflow: () -> Unit,
    onBack: () -> Unit,
    onPage: (Int, Int) -> Unit
) {
    val pager = rememberPagerState(
        initialPage = startPage.coerceIn(0, maxOf(0, engine.pageCount - 1))
    ) { engine.pageCount }
    val nav = rememberCoroutineScope()
    var showBookmarks by remember { mutableStateOf(false) }
    // The first page keeps the scroll position we are resuming at; later pages start at the top.
    val resuming = remember { booleanArrayOf(true) }
    LaunchedEffect(pager.currentPage) {
        val sc = if (resuming[0] && pager.currentPage == startPage) startScroll else 0
        resuming[0] = false
        onPage(pager.currentPage, sc)
    }
    val here = pager.currentPage
    val marked = here in bookmarks
    fun jump(delta: Int) {
        val target = (pager.currentPage + delta).coerceIn(0, maxOf(0, engine.pageCount - 1))
        nav.launch { pager.scrollToPage(target) }
    }

    val shape = RoundedCornerShape(18.dp)
    val zoomState = rememberUpdatedState(onZoom)
    val zoomNow = rememberUpdatedState(zoom)
    Box(Modifier.fillMaxSize().background(Pal.Dark)) {
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogoTile(onBack)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "A\u2212",
                    modifier = Modifier.clickable { onZoom(zoom - 0.2f) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 18.sp)
                )
                BasicText(
                    "A+",
                    modifier = Modifier.clickable { onZoom(zoom + 0.2f) }.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 22.sp)
                )
                BasicText(
                    "\u00B6",
                    modifier = Modifier.clickable(onClick = onReflow).padding(horizontal = 10.dp, vertical = 6.dp),
                    style = TextStyle(color = if (reflow) Pal.Leaf else Pal.Ink, fontSize = 24.sp)
                )
                BasicText(
                    if (night) "\u25D0" else "\u25CB",
                    modifier = Modifier.clickable(onClick = onNight).padding(10.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 22.sp)
                )
                BasicText(
                    if (marked) "\u2605" else "\u2606",
                    modifier = Modifier.clickable { onBookmark(here) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = TextStyle(color = if (marked) Pal.Leaf else Pal.Ink, fontSize = 26.sp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill(if (bookmarks.isEmpty()) "Bookmarks" else "Bookmarks \u00B7 ${bookmarks.size}", { showBookmarks = true })
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "\u2039",
                    modifier = Modifier.clickable { jump(-10) }.padding(horizontal = 14.dp, vertical = 2.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 34.sp)
                )
                Pill("${pager.currentPage + 1} / ${engine.pageCount}", null)
                BasicText(
                    "\u203A",
                    modifier = Modifier.clickable { jump(10) }.padding(horizontal = 14.dp, vertical = 2.dp),
                    style = TextStyle(color = Pal.Ink, fontSize = 34.sp)
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                .clip(shape)
                .background(if (night) Pal.Deep else Color.White)
                .border(2.dp, Pal.Mid, shape)
                .padding(2.dp)
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // Two-finger pinch zooms; single-finger drags pass through untouched.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) zoomState.value(zoomNow.value * z)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            ) {
                val wPx = constraints.maxWidth
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { i ->
                    val rf = engine.reflow
                    if (reflow && rf != null) {
                        ReflowView(
                            engine, rf, i, zoom, night,
                            initialScroll = if (i == startPage) startScroll else 0,
                            onScroll = { v -> if (i == pager.currentPage) onPage(i, v) }
                        )
                    } else {
                        PageView(
                            engine, i, wPx, zoom, night,
                            onDoubleTap = {
                                val next = listOf(1.8f, 2.6f, 3.6f).firstOrNull { it > zoom + 0.05f } ?: 1f
                                onZoom(next)
                            }
                        )
                    }
                }
            }
        }
    }
    if (showBookmarks) {
        Box(
            Modifier.fillMaxSize().background(Color(0xB3000000)).clickable { showBookmarks = false },
            contentAlignment = Alignment.Center
        ) {
            val card = RoundedCornerShape(18.dp)
            Column(
                Modifier.padding(24.dp).fillMaxWidth()
                    .clip(card).background(Pal.Deep).border(2.dp, Pal.Mid, card)
                    .clickable(enabled = false) { }
                    .padding(18.dp)
            ) {
                BasicText("Bookmarks", style = TextStyle(color = Pal.Ink, fontSize = 20.sp, fontFamily = FontFamily.Serif))
                Spacer(Modifier.size(10.dp))
                if (bookmarks.isEmpty()) {
                    BasicText(
                        "No bookmarks yet. Tap \u2606 at the top to bookmark the page you are on.",
                        style = TextStyle(color = Pal.Dim, fontSize = 15.sp, lineHeight = 21.sp)
                    )
                } else {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        bookmarks.forEach { pg ->
                            val snippet = engine.reflow?.cached(pg)?.firstOrNull { it.text.length > 3 }?.text?.take(48)
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f)
                                        .clickable {
                                            showBookmarks = false
                                            nav.launch { pager.scrollToPage(pg.coerceIn(0, maxOf(0, engine.pageCount - 1))) }
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    BasicText("Page ${pg + 1}", style = TextStyle(color = Pal.Ink, fontSize = 17.sp))
                                    if (snippet != null) {
                                        BasicText(
                                            snippet,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = TextStyle(color = Pal.Dim, fontSize = 13.sp)
                                        )
                                    }
                                }
                                BasicText(
                                    "\u2715",
                                    modifier = Modifier.clickable { onBookmark(pg) }.padding(12.dp),
                                    style = TextStyle(color = Pal.Dim, fontSize = 17.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PageView(
    engine: PdfEngine,
    index: Int,
    panelWidthPx: Int,
    zoom: Float,
    night: Boolean,
    onDoubleTap: () -> Unit
) {
    // Render at the displayed size (plus a little extra) so text stays crisp at any zoom.
    val target = (panelWidthPx * zoom * 1.15f).toInt().coerceIn(500, 3000)

    var bmp by remember(engine, index) { mutableStateOf(engine.cached(index)) }
    LaunchedEffect(engine, index, target) {
        val b = bmp
        if (b == null || b.width < target * 0.9f) {
            engine.page(index, target)?.let { bmp = it }
        }
    }

    val pageWidth = with(LocalDensity.current) { (panelWidthPx * zoom).toDp() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        val b = bmp
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                colorFilter = if (night) NightFilter else null,
                modifier = Modifier.width(pageWidth)
            )
        }
    }
}

/**
 * Reflowed text of one page. Body text is [zoom]x the size it has in fit-to-width page view
 * (so 2x = double); titles keep the size they have in page view, whatever the zoom.
 * Pages with (almost) no text - covers, picture pages, section openers - show the original
 * page instead, and every page can show its original underneath so figures are never lost.
 */
@Composable
private fun ReflowView(
    engine: PdfEngine,
    rf: ReflowEngine,
    index: Int,
    zoom: Float,
    night: Boolean,
    initialScroll: Int,
    onScroll: (Int) -> Unit
) {
    var paras by remember(rf, index) { mutableStateOf(rf.cached(index)) }
    LaunchedEffect(rf, index) {
        if (paras == null) paras = rf.page(index)
        rf.prefetch(index)
    }
    val p = paras
    val chars = p?.sumOf { it.text.length } ?: 0
    val textPoor = p != null && chars < 120
    var showOriginal by remember(rf, index) { mutableStateOf(false) }
    val needImage = textPoor || showOriginal

    val scroll = rememberScrollState()
    // Resume: wait until the text has been laid out tall enough, then jump to the saved spot.
    LaunchedEffect(rf, index, p != null) {
        if (p != null && initialScroll > 0) {
            withTimeoutOrNull(2500) { snapshotFlow { scroll.maxValue }.first { it >= initialScroll } }
            scroll.scrollTo(initialScroll)
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value }.collectLatest { v ->
            delay(400)
            onScroll(v)
        }
    }

    val density = LocalDensity.current
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val imgTarget = with(density) { (screenDp.dp.toPx() * 1.15f).toInt() }.coerceIn(500, 2200)
    var bmp by remember(engine, index) { mutableStateOf(engine.cached(index)) }
    LaunchedEffect(engine, index, needImage) {
        if (needImage && bmp == null) engine.page(index, imgTarget)?.let { bmp = it }
    }

    val ink = if (night) Pal.Ink else Color(0xFF1E211D)
    // dp on screen per point of the PDF page, as in fit-to-width page view
    val unit = (screenDp - 32f) / rf.pageWidthPt
    val bodyPt = if (rf.bodyPt > 0f) rf.bodyPt else 11f
    val bodySp = (bodyPt * unit * zoom).coerceAtLeast(14f)

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (p == null) {
            BasicText("Loading\u2026", style = TextStyle(color = Pal.Dim, fontSize = 16.sp))
        } else {
            if (!textPoor) {
                p.forEach { para ->
                    val title = para.heading > 0f
                    val sp = if (title) (para.heading * bodyPt * unit).coerceAtLeast(16f) else bodySp
                    BasicText(
                        para.text,
                        modifier = Modifier.fillMaxWidth().padding(
                            top = if (title) (sp * 0.5f).dp else 0.dp,
                            bottom = (sp * 0.6f).dp
                        ),
                        style = TextStyle(
                            color = ink,
                            fontSize = sp.sp,
                            lineHeight = (sp * 1.4f).sp,
                            fontWeight = if (title) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = 0.sp
                        )
                    )
                }
            }
            if (needImage) {
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
                } else {
                    BasicText("Loading page\u2026", style = TextStyle(color = Pal.Dim, fontSize = 14.sp))
                }
            }
            if (!textPoor) {
                BasicText(
                    if (showOriginal) "Hide original page \u25B4" else "Show original page \u25BE",
                    modifier = Modifier.padding(top = 6.dp).clickable { showOriginal = !showOriginal }.padding(vertical = 8.dp),
                    style = TextStyle(color = Pal.Dim, fontSize = 14.sp)
                )
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}
