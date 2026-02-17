package com.example.sf_televideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern

private const val TV_COLS = 40
private const val TV_ROWS = 24

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TelevideoApp()
            }
        }
    }
}

/* -------------------- Networking + Parsing -------------------- */

private fun buildTelevideoUrl(page: String): String =
    "https://www.servizitelevideo.rai.it/televideo/pub/solotesto.jsp?pagina=$page"

/**
 * Estrae testo preservando al massimo newline e spazi:
 * - se c'è <pre>, è tipicamente il più fedele
 * - altrimenti wholeText()
 * - fallback a text()
 */
private fun extractTelevideoText(html: String): String {
    val doc = Jsoup.parse(html)

    val pre = doc.selectFirst("pre")
    if (pre != null) return pre.wholeText().trimEnd()

    val body = doc.body()
    val whole = body?.wholeText()?.trimEnd()
    if (!whole.isNullOrBlank()) return whole

    return body?.text().orEmpty()
}

private suspend fun fetchPageRawText(client: OkHttpClient, page: String): String =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(buildTelevideoUrl(page)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")
            val html = response.body?.string().orEmpty()
            extractTelevideoText(html)
        }
    }

/**
 * Estrae numeri pagina dal testo, con filtro:
 * - solo 3 cifre
 * - range 100..899
 * - ordina
 */
private fun extractPageNumbers(text: String): List<String> {
    val pages = mutableSetOf<Int>()
    val pattern = Pattern.compile("\\b[pP]?\\s*(\\d{3})\\b")
    val matcher = pattern.matcher(text)
    while (matcher.find()) {
        val n = matcher.group(1)?.toIntOrNull() ?: continue
        if (n in 100..899) pages.add(n)
    }
    return pages.sorted().map { it.toString() }
}

/* -------------------- TV GRID: 40x24 -------------------- */

private fun normalizeToGrid(raw: String, cols: Int = TV_COLS, rows: Int = TV_ROWS): String {
    val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    val fixed = lines.take(rows).map { line ->
        // IMPORTANTISSIMO: niente trim(), altrimenti perdi allineamento
        val truncated = if (line.length > cols) line.substring(0, cols) else line
        truncated.padEnd(cols, ' ')
    }.toMutableList()

    while (fixed.size < rows) fixed.add("".padEnd(cols, ' '))

    return fixed.joinToString("\n")
}

/**
 * Dato il tap in (row,col), cerca una tripletta di cifre 3-digit (100..899) in quella zona.
 * Questo trasforma i numeri "stampati" nella pagina in link.
 */
private fun findPageAt(lines: List<String>, row: Int, col: Int): String? {
    if (row !in lines.indices) return null
    val line = lines[row]
    if (line.isEmpty()) return null
    val safeCol = col.coerceIn(0, line.length - 1)

    // Cerchiamo una tripletta di cifre che includa la colonna cliccata.
    val candidates = listOf(safeCol - 2, safeCol - 1, safeCol)
    for (start in candidates) {
        if (start < 0 || start + 3 > line.length) continue
        val chunk = line.substring(start, start + 3)
        if (chunk.all { it.isDigit() }) {
            val n = chunk.toIntOrNull() ?: continue
            if (n in 100..899) return chunk
        }
    }

    // Caso: clic vicino (spazio prima/dopo). Allargo una finestra e cerco \bddd\b.
    val windowStart = (safeCol - 4).coerceAtLeast(0)
    val windowEnd = (safeCol + 5).coerceAtMost(line.length)
    val window = line.substring(windowStart, windowEnd)

    val match = Regex("""\b(\d{3})\b""").find(window) ?: return null
    val hit = match.groupValues[1]
    val n = hit.toIntOrNull() ?: return null
    return if (n in 100..899) hit else null
}

/* -------------------- Televideo TV Renderer (autofit + clickable numbers) -------------------- */

@Composable
private fun TelevideoTvScreen(
    rawText: String,
    page: String,
    modifier: Modifier = Modifier,
    padding: Dp = 12.dp,
    onPageClick: (String) -> Unit
) {
    val bg = Color(0xFF000000)
    val fg = Color(0xFF00FF66)

    val gridText = remember(rawText) { normalizeToGrid(rawText) }
    val gridLines = remember(gridText) { gridText.split("\n") }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.background(bg)
    ) {
        val contentWidthPx = with(density) { (maxWidth - padding * 2).toPx().coerceAtLeast(1f) }

        // Autoadatta font per 40 colonne
        val probe40 = remember { "M".repeat(TV_COLS) }

        val fontSizeSp = remember(contentWidthPx) {
            var low = 8f
            var high = 28f
            repeat(14) {
                val mid = (low + high) / 2f
                val layout = measurer.measure(
                    text = probe40,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = mid.sp)
                )
                if (layout.size.width.toFloat() <= contentWidthPx) low = mid else high = mid
            }
            low.sp
        }

        val lineHeightSp = (fontSizeSp.value * 1.15f).sp

        // Misure in px per convertire tap -> colonna/riga
        val charWidthPx = remember(fontSizeSp) {
            measurer.measure(
                text = "M",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp)
            ).size.width.toFloat().coerceAtLeast(1f)
        }
        val lineHeightPx = with(density) { lineHeightSp.toPx().coerceAtLeast(1f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header minimal "TV-like"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RAI Televideo",
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSizeSp.value * 0.90f).sp
                )
                Text(
                    text = "PAG. $page",
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSizeSp.value * 0.90f).sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // Viewer: tap -> (row,col) -> pagina
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(gridLines, charWidthPx, lineHeightPx) {
                        detectTapGestures { offset ->
                            val col = (offset.x / charWidthPx).toInt()
                            val row = (offset.y / lineHeightPx).toInt()

                            val hit = findPageAt(gridLines, row, col)
                            if (hit != null) onPageClick(hit)
                        }
                    }
            ) {
                Text(
                    text = gridText,
                    color = fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSizeSp,
                    lineHeight = lineHeightSp,
                    textAlign = TextAlign.Left,
                    softWrap = false,           // FONDAMENTALE: NO WRAP
                    maxLines = TV_ROWS,
                    overflow = TextOverflow.Clip,
                    style = TextStyle(
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Top,
                            trim = LineHeightStyle.Trim.None
                        )
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/* -------------------- App UI -------------------- */

@Composable
fun TelevideoApp() {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    var rawContent by remember { mutableStateOf("Premi il bottone per caricare Televideo pagina 100") }
    var pageNumbers by remember { mutableStateOf(emptyList<String>()) }
    var currentPage by remember { mutableStateOf("100") }

    // Cancella richiesta precedente se l'utente clicca rapidamente
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun loadPage(page: String, alsoExtractPages: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            rawContent = "Caricando pagina $page..."
            try {
                val text = fetchPageRawText(client, page)
                currentPage = page
                rawContent = text

                if (alsoExtractPages) {
                    pageNumbers = extractPageNumbers(text)
                }
            } catch (e: CancellationException) {
                // ignorata: l'utente ha cliccato un'altra pagina
            } catch (e: Exception) {
                rawContent = "❌ Errore: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Bottone pagina 100
        Button(
            onClick = { loadPage("100", alsoExtractPages = true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔴 Carica Televideo Pagina 100")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Lista pagine (opzionale; utile per debug e navigazione rapida)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            if (pageNumbers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Carica la pagina 100 per vedere riferimenti a pagine.")
                }
            } else {
                LazyColumn {
                    items(pageNumbers.take(120)) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { loadPage(page, alsoExtractPages = false) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📄 $page",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp
                            )
                            if (page == currentPage) Text("▶", fontSize = 16.sp)
                        }
                        Divider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Viewer stile TV (40x24) + numeri cliccabili
        Card(modifier = Modifier.fillMaxSize()) {
            TelevideoTvScreen(
                rawText = rawContent,
                page = currentPage,
                modifier = Modifier.fillMaxSize(),
                onPageClick = { tappedPage ->
                    // Qui è la magia: numeri nella pagina diventano link
                    loadPage(tappedPage, alsoExtractPages = false)
                }
            )
        }
    }
}
