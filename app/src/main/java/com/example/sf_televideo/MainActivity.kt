@file:Suppress("SpellCheckingInspection")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val TV_COLS = 40
private const val TV_ROWS = 24
private const val SCALE_Y = 2.5f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TelevideoApp() } }
    }
}

/* ---------------- URLs ---------------- */

private fun textUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/solotesto.jsp?pagina=$page"

private fun imageUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$page.png"

private fun pageMapUrl(page: String) =
    "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

/* ---------------- Networking ---------------- */

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

/* ---------------- Teletext grid helpers ---------------- */

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

/* ---------------- Auto-crop black borders ---------------- */

private fun isNearBlack(argb: Int, thr: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = (argb) and 0xFF
    return r <= thr && g <= thr && b <= thr
}

private fun autoCropBlackBars(
    src: Bitmap,
    blackThreshold: Int = 18,
    requireRatio: Float = 0.985f,
    maxCropFrac: Float = 0.12f
): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 40 || h < 40) return src

    val stepY = max(1, h / 120)
    val stepX = max(1, w / 120)

    fun colIsMostlyBlack(x: Int): Boolean {
        var total = 0
        var black = 0
        var y = 0
        while (y < h) {
            if (isNearBlack(src[x, y], blackThreshold)) black++
            total++
            y += stepY
        }
        return black.toFloat() / total >= requireRatio
    }

    fun rowIsMostlyBlack(y: Int): Boolean {
        var total = 0
        var black = 0
        var x = 0
        while (x < w) {
            if (isNearBlack(src[x, y], blackThreshold)) black++
            total++
            x += stepX
        }
        return black.toFloat() / total >= requireRatio
    }

    val maxCropX = (w * maxCropFrac).toInt()
    val maxCropY = (h * maxCropFrac).toInt()

    var left = 0
    while (left < min(maxCropX, w - 2) && colIsMostlyBlack(left)) left++

    var right = w - 1
    while (right > max(w - 1 - maxCropX, 1) && colIsMostlyBlack(right)) right--

    var top = 0
    while (top < min(maxCropY, h - 2) && rowIsMostlyBlack(top)) top++

    var bottom = h - 1
    while (bottom > max(h - 1 - maxCropY, 1) && rowIsMostlyBlack(bottom)) bottom--

    val newW = right - left + 1
    val newH = bottom - top + 1

    if (newW <= 0 || newH <= 0) return src
    if (newW < w * 0.6f || newH < h * 0.6f) return src
    if (left == 0 && right == w - 1 && top == 0 && bottom == h - 1) return src

    return Bitmap.createBitmap(src, left, top, newW, newH)
}

/* ---------------- Subpages heuristic (still stub, but compiles) ---------------- */

private suspend fun fetchSubpageLink(
    client: OkHttpClient,
    page: String,
    direction: Int
): String? = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(pageMapUrl(page)).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@withContext null
        val html = resp.body?.string().orEmpty()
        val doc = Jsoup.parse(html, "https://www.servizitelevideo.rai.it/")
        val links = doc.select("a[href]")

        val keywords = if (direction < 0)
            listOf("prev", "preced", "indietro", "su", "up", "↑", "^")
        else
            listOf("next", "success", "avanti", "giu", "giù", "down", "↓", "v")

        for (a in links) {
            val href = a.absUrl("href")
            if (href.isBlank()) continue
            val hay = (a.text() + " " + a.attr("title") + " " + a.className() + " " + href).lowercase()
            if (keywords.any { it in hay }) return@withContext href
        }
        null
    }
}

/* ---------------- Bookmarks dialog ---------------- */

@Composable
private fun BookmarksDialog(
    bookmarks: List<Int>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks.\nLong-press ★ to add the current page.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    bookmarks.forEach { p ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onSelect(p) },
                                    onLongClick = { /* optional future: remove */ }
                                )
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = p.toString(),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/* ---------------- Viewer ---------------- */

@Composable
private fun TelevideoViewer(
    bitmap: Bitmap?,
    grid: List<String>,
    modifier: Modifier = Modifier,
    onTapPage: (String) -> Unit,
    onLongPressPage: (String) -> Unit
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .verticalScroll(scroll),
        contentAlignment = Alignment.TopCenter
    ) {
        if (bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading…", color = Color.White)
            }
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, grid, widthPx, scaledHeightPx) {
                            detectTapGestures(
                                onTap = { tap ->
                                    val w = widthPx.toFloat().coerceAtLeast(1f)
                                    val h = scaledHeightPx.coerceAtLeast(1f)
                                    val col = floor((tap.x / w) * TV_COLS).toInt().coerceIn(0, TV_COLS - 1)
                                    val row = floor((tap.y / h) * TV_ROWS).toInt().coerceIn(0, TV_ROWS - 1)
                                    val hit = findPageAt(grid, row, col)
                                    if (hit != null) onTapPage(hit)
                                },
                                onLongPress = { tap ->
                                    val w = widthPx.toFloat().coerceAtLeast(1f)
                                    val h = scaledHeightPx.coerceAtLeast(1f)
                                    val col = floor((tap.x / w) * TV_COLS).toInt().coerceIn(0, TV_COLS - 1)
                                    val row = floor((tap.y / h) * TV_ROWS).toInt().coerceIn(0, TV_ROWS - 1)
                                    val hit = findPageAt(grid, row, col)
                                    if (hit != null) onLongPressPage(hit)
                                }
                            )
                        }
                )
            }
        }
    }
}

/* ---------------- Top bar (tap vs long press) ---------------- */

@Composable
private fun TvNavButton(
    label: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color.White, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TvNavBar(
    currentPage: String,
    onGoTo: (String) -> Unit,
    onSubUp: (Int) -> Unit,
    onSubDown: (Int) -> Unit,
    onStarTap: () -> Unit,
    onStarLongPress: () -> Unit
) {
    fun clamp(p: Int) = p.coerceIn(100, 899)
    val cur = currentPage.toIntOrNull() ?: 100

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvNavButton("★", onTap = onStarTap, onLongPress = onStarLongPress)
            TvNavButton("100", onTap = { onGoTo("100") }, onLongPress = { onGoTo("101") })
            TvNavButton("101", onTap = { onGoTo("101") }, onLongPress = { onGoTo("102") })
            TvNavButton("103", onTap = { onGoTo("103") }, onLongPress = { onGoTo("104") })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TvNavButton("<", onTap = { onGoTo(clamp(cur - 1).toString()) }, onLongPress = { onGoTo(clamp(cur - 10).toString()) })
            TvNavButton(">", onTap = { onGoTo(clamp(cur + 1).toString()) }, onLongPress = { onGoTo(clamp(cur + 10).toString()) })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TvNavButton("^", onTap = { onSubUp(1) }, onLongPress = { onSubUp(5) })
            TvNavButton("v", onTap = { onSubDown(1) }, onLongPress = { onSubDown(5) })
        }
    }
}

/* ---------------- App ---------------- */

@Composable
fun TelevideoApp() {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    var currentPage by remember { mutableStateOf("100") }
    var grid by remember { mutableStateOf(normalizeGrid("")) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val bookmarks = remember { mutableStateListOf<Int>() }
    var showBookmarks by remember { mutableStateOf(false) }

    fun load(page: String) {
        scope.launch {
            try {
                val t = async { fetchText(client, page) }
                val b = async {
                    val raw = fetchBitmap(client, page)
                    autoCropBlackBars(raw)
                }
                val text = t.await()
                val bmp = b.await()

                currentPage = page
                grid = normalizeGrid(text)
                bitmap = bmp
            } catch (_: Exception) {
                // keep last good page
            }
        }
    }

    fun subNav(direction: Int, step: Int) {
        scope.launch {
            try {
                repeat(step) {
                    val link = fetchSubpageLink(client, currentPage, direction) ?: return@launch
                    val m = Pattern.compile("[?&]pagina=(\\d{3})").matcher(link)
                    var p: String? = null
                    while (m.find()) p = m.group(1)
                    if (p != null) load(p) else return@launch
                }
            } catch (_: Exception) {
                // keep last good page
            }
        }
    }

    fun addCurrentToBookmarks() {
        val p = currentPage.toIntOrNull() ?: return
        if (!bookmarks.contains(p)) {
            bookmarks.add(p)
            bookmarks.sort()
        }
    }

    LaunchedEffect(Unit) { load("100") }

    Column(Modifier.fillMaxSize()) {

        TvNavBar(
            currentPage = currentPage,
            onGoTo = { p -> load(p) },
            onSubUp = { step -> subNav(-1, step) },
            onSubDown = { step -> subNav(+1, step) },
            onStarTap = { showBookmarks = true },
            onStarLongPress = { addCurrentToBookmarks() }
        )

        TelevideoViewer(
            bitmap = bitmap,
            grid = grid,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onTapPage = { tapped -> load(tapped) },
            onLongPressPage = { tapped ->
                val p = tapped.toIntOrNull()
                if (p != null && !bookmarks.contains(p)) {
                    bookmarks.add(p)
                    bookmarks.sort()
                }
            }
        )

        if (showBookmarks) {
            BookmarksDialog(
                bookmarks = bookmarks,
                onDismiss = { showBookmarks = false },
                onSelect = { p ->
                    showBookmarks = false
                    load(p.toString())
                }
            )
        }
    }
}
