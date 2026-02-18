package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.regex.Pattern
import kotlin.math.floor

private const val TV_COLS = 40
private const val TV_ROWS = 24
private const val SCALE_Y = 2f

private sealed class Hotspot(val targetPage: String) {
    class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, targetPage: String) : Hotspot(targetPage)
    class Poly(val points: List<Pair<Int, Int>>, targetPage: String) : Hotspot(targetPage)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TelevideoApp() } }
    }
}

/* ---------------- URL ---------------- */

private fun textUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/solotesto.jsp?pagina=$page"

private fun imageUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$page.png"

private fun pageMapUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

/* ---------------- NETWORK ---------------- */

private fun extractText(html: String): String {
    val doc = Jsoup.parse(html)
    val pre = doc.selectFirst("pre")
    return pre?.wholeText()?.trimEnd()
        ?: doc.body()?.wholeText()?.trimEnd().orEmpty()
}

private suspend fun fetchText(client: OkHttpClient, page: String): String =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(textUrl(page)).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            extractText(it.body?.string().orEmpty())
        }
    }

private suspend fun fetchBitmap(client: OkHttpClient, page: String): Bitmap =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(imageUrl(page)).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val bytes = it.body?.bytes() ?: throw IOException("Empty image")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Decode failed")
        }
    }

private fun extractPages(text: String): List<String> {
    val set = mutableSetOf<Int>()
    val m = Pattern.compile("\\b(\\d{3})\\b").matcher(text)
    while (m.find()) {
        val n = m.group(1)?.toIntOrNull() ?: continue
        if (n in 100..899) set.add(n)
    }
    return set.sorted().map { it.toString() }
}

/* ---------------- GRID fallback ---------------- */

private fun normalizeGrid(raw: String): List<String> {
    val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val fixed = lines.take(TV_ROWS).map {
        val t = if (it.length > TV_COLS) it.substring(0, TV_COLS) else it
        t.padEnd(TV_COLS, ' ')
    }.toMutableList()
    while (fixed.size < TV_ROWS) fixed.add("".padEnd(TV_COLS, ' '))
    return fixed
}

private fun findPageAt(lines: List<String>, row: Int, col: Int): String? {
    if (row !in lines.indices) return null
    val line = lines[row]
    if (line.isEmpty()) return null
    val c = col.coerceIn(0, line.length - 1)

    for (start in listOf(c - 2, c - 1, c)) {
        if (start < 0 || start + 3 > line.length) continue
        val chunk = line.substring(start, start + 3)
        if (chunk.all { it.isDigit() }) {
            val n = chunk.toIntOrNull() ?: continue
            if (n in 100..899) return chunk
        }
    }
    return null
}

/* ---------------- IMAGE MAP parsing ---------------- */

private fun extractTargetPageFromHref(href: String?): String? {
    if (href.isNullOrBlank()) return null
    val m = Pattern.compile("pagina=(\\d{3})").matcher(href)
    return if (m.find()) m.group(1) else null
}

private fun parseCoords(coords: String?): List<Int> {
    if (coords.isNullOrBlank()) return emptyList()
    return coords.split(",").mapNotNull { it.trim().toIntOrNull() }
}

private fun parseHotspots(doc: Document): List<Hotspot> {
    val out = mutableListOf<Hotspot>()
    val areas = doc.select("map area, area")
    for (a in areas) {
        val target = extractTargetPageFromHref(a.attr("href")) ?: continue
        val shape = a.attr("shape").lowercase().ifBlank { "rect" }
        val coords = parseCoords(a.attr("coords"))

        when (shape) {
            "rect" -> if (coords.size >= 4) {
                out.add(Hotspot.Rect(coords[0], coords[1], coords[2], coords[3], target))
            }
            "poly", "polygon" -> if (coords.size >= 6 && coords.size % 2 == 0) {
                val pts = coords.chunked(2).map { (x, y) -> x to y }
                out.add(Hotspot.Poly(pts, target))
            }
        }
    }
    return out
}

private fun pointInPoly(x: Float, y: Float, pts: List<Pair<Int, Int>>): Boolean {
    var inside = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val xi = pts[i].first.toFloat()
        val yi = pts[i].second.toFloat()
        val xj = pts[j].first.toFloat()
        val yj = pts[j].second.toFloat()

        val intersects = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1f) + xi)

        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun hitTestHotspots(hotspots: List<Hotspot>, xImg: Float, yImg: Float): String? {
    for (h in hotspots) {
        when (h) {
            is Hotspot.Rect -> {
                val left = minOf(h.x1, h.x2).toFloat()
                val right = maxOf(h.x1, h.x2).toFloat()
                val top = minOf(h.y1, h.y2).toFloat()
                val bottom = maxOf(h.y1, h.y2).toFloat()
                if (xImg in left..right && yImg in top..bottom) return h.targetPage
            }
            is Hotspot.Poly -> {
                if (pointInPoly(xImg, yImg, h.points)) return h.targetPage
            }
        }
    }
    return null
}

private suspend fun fetchHotspots(client: OkHttpClient, page: String): List<Hotspot> =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(pageMapUrl(page)).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) return@withContext emptyList()
            val html = it.body?.string().orEmpty()
            parseHotspots(Jsoup.parse(html))
        }
    }

/* ---------------- VIEWER ---------------- */

@Composable
private fun TelevideoViewer(
    bitmap: Bitmap?,
    grid: List<String>,
    hotspots: List<Hotspot>,
    pageKey: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onPageClick: (String) -> Unit
) {
    val scroll = rememberScrollState()

    // ✅ fondamentale: quando cambia pagina, torni sempre in alto
    LaunchedEffect(pageKey) {
        scroll.scrollTo(0)
    }

    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier.verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter
    ) {
        if (bitmap == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) { Text("Caricamento…") }
            return@Box
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width }
        ) {
            val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
            val imgH = bitmap.height.toFloat().coerceAtLeast(1f)

            val baseHeightPx = (widthPx.toFloat() * (imgH / imgW)).coerceAtLeast(1f)
            val scaledHeightPx = (baseHeightPx * SCALE_Y).coerceAtLeast(1f)
            val scaledHeightDp = with(density) { scaledHeightPx.toDp() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledHeightDp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Televideo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                // piccolo overlay di loading (non rompe i tap perché è in alto e non blocca)
                if (isLoading) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Caricamento…") }
                        )
                    }
                }

                // ✅ blocca tap mentre carica (evita stati strani)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, grid, hotspots, widthPx, scaledHeightPx, isLoading) {
                            detectTapGestures { tap ->
                                if (isLoading) return@detectTapGestures

                                val w = widthPx.toFloat().coerceAtLeast(1f)
                                val h = scaledHeightPx.coerceAtLeast(1f)

                                val xImg = (tap.x / w) * bitmap.width.toFloat()
                                val yImg = (tap.y / h) * bitmap.height.toFloat()

                                val hitByMap = hitTestHotspots(hotspots, xImg, yImg)
                                if (hitByMap != null) {
                                    onPageClick(hitByMap)
                                    return@detectTapGestures
                                }

                                val col = floor((tap.x / w) * TV_COLS).toInt().coerceIn(0, TV_COLS - 1)
                                val row = floor((tap.y / h) * TV_ROWS).toInt().coerceIn(0, TV_ROWS - 1)
                                val hitByGrid = findPageAt(grid, row, col)
                                if (hitByGrid != null) onPageClick(hitByGrid)
                            }
                        }
                )
            }
        }
    }
}

/* ---------------- APP ---------------- */

@Composable
fun TelevideoApp() {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    var currentPage by remember { mutableStateOf("100") }
    var grid by remember { mutableStateOf(normalizeGrid("")) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageList by remember { mutableStateOf(emptyList<String>()) }
    var hotspots by remember { mutableStateOf(emptyList<Hotspot>()) }
    var isLoading by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }

    fun load(page: String, extractList: Boolean) {
        job?.cancel()
        job = scope.launch {
            try {
                isLoading = true

                // ✅ NON azzerare bitmap: mantieni la pagina visibile finché arriva la nuova
                val t = async { fetchText(client, page) }
                val b = async { fetchBitmap(client, page) }
                val m = async { fetchHotspots(client, page) }

                val text = t.await()
                val bmp = b.await()
                val hs = m.await()

                currentPage = page
                grid = normalizeGrid(text)
                bitmap = bmp
                hotspots = hs

                if (extractList) pageList = extractPages(text)
            } catch (e: Exception) {
                grid = normalizeGrid("Errore: ${e.message}")
                // qui puoi scegliere: lasciare bitmap precedente o metterla a null
                // bitmap = null
                hotspots = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = { load("100", true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔴 Carica Televideo Pagina 100")
        }

        Spacer(Modifier.height(12.dp))

        Card(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            if (pageList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Carica la 100 per iniziare")
                }
            } else {
                LazyColumn {
                    items(pageList.take(120)) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { load(p, false) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("📄 $p")
                            if (p == currentPage) Text("▶")
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxSize()) {
            TelevideoViewer(
                bitmap = bitmap,
                grid = grid,
                hotspots = hotspots,
                pageKey = currentPage,
                isLoading = isLoading,
                modifier = Modifier.fillMaxSize(),
                onPageClick = { tapped -> load(tapped, false) }
            )
        }
    }
}
