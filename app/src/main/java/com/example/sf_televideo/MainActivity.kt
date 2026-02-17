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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern
import kotlin.math.floor

private const val TV_COLS = 40
private const val TV_ROWS = 24

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TelevideoApp() } }
    }
}

/* -------------------- URLS -------------------- */

private fun textUrl(page: String): String =
    "https://www.servizitelevideo.rai.it/televideo/pub/solotesto.jsp?pagina=$page"

private fun imageUrl(page: String): String =
    "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$page.png"

/* -------------------- NETWORK -------------------- */

private fun extractText(html: String): String {
    val doc = Jsoup.parse(html)
    val pre = doc.selectFirst("pre")
    if (pre != null) return pre.wholeText().trimEnd()
    val body = doc.body()
    val whole = body?.wholeText()?.trimEnd()
    if (!whole.isNullOrBlank()) return whole
    return body?.text().orEmpty()
}

private suspend fun fetchText(client: OkHttpClient, page: String): String =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(textUrl(page)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            extractText(resp.body?.string().orEmpty())
        }
    }

private suspend fun fetchBitmap(client: OkHttpClient, page: String): Bitmap =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(imageUrl(page)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("Empty image")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Decode bitmap failed")
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

/* -------------------- GRID (per hit-test numeri) -------------------- */

private fun normalizeGrid(raw: String): List<String> {
    val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val fixed = lines.take(TV_ROWS).map { line ->
        val t = if (line.length > TV_COLS) line.substring(0, TV_COLS) else line
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
    val starts = listOf(c - 2, c - 1, c)

    for (s in starts) {
        if (s < 0 || s + 3 > line.length) continue
        val chunk = line.substring(s, s + 3)
        if (chunk.all { it.isDigit() }) {
            val n = chunk.toIntOrNull() ?: continue
            if (n in 100..899) return chunk
        }
    }
    return null
}

/* -------------------- VIEWER: PNG 16:9 + overlay cliccabile (NO BoxWithConstraints) -------------------- */

@Composable
private fun TelevideoViewer(
    bitmap: Bitmap?,
    grid: List<String>,
    modifier: Modifier = Modifier,
    onPageClick: (String) -> Unit
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onSizeChanged { sizePx = it } // <-- qui abbiamo larghezza/altezza REALI in px
        ) {
            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Caricamento…")
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Televideo",
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay tap
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(grid, sizePx) {
                            detectTapGestures { offset ->
                                // Se ancora non abbiamo size valida, esci
                                val w = sizePx.width.toFloat().coerceAtLeast(1f)
                                val h = sizePx.height.toFloat().coerceAtLeast(1f)

                                val cellWpx = (w / TV_COLS).coerceAtLeast(1f)
                                val cellHpx = (h / TV_ROWS).coerceAtLeast(1f)

                                val col = floor(offset.x / cellWpx).toInt()
                                    .coerceIn(0, TV_COLS - 1)
                                val row = floor(offset.y / cellHpx).toInt()
                                    .coerceIn(0, TV_ROWS - 1)

                                val hit = findPageAt(grid, row, col)
                                if (hit != null) onPageClick(hit)
                            }
                        }
                )
            }
        }
    }
}

/* -------------------- APP -------------------- */

@Composable
fun TelevideoApp() {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    var currentPage by remember { mutableStateOf("100") }
    var rawText by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(normalizeGrid("")) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageList by remember { mutableStateOf(emptyList<String>()) }

    var job by remember { mutableStateOf<Job?>(null) }

    fun load(page: String, extractList: Boolean) {
        job?.cancel()
        job = scope.launch {
            try {
                bitmap = null

                val t = async { fetchText(client, page) }
                val b = async { fetchBitmap(client, page) }

                val text = t.await()
                val bmp = b.await()

                currentPage = page
                rawText = text
                grid = normalizeGrid(text)
                bitmap = bmp

                if (extractList) pageList = extractPages(text)
            } catch (e: CancellationException) {
                // ignorata
            } catch (e: Exception) {
                rawText = "Errore: ${e.message}"
                grid = normalizeGrid(rawText)
                bitmap = null
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
                modifier = Modifier.fillMaxSize(),
                onPageClick = { tapped -> load(tapped, false) }
            )
        }
    }
}
