package com.example.sf_televideo

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// DEBUG overlay imports
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint

@Composable
fun ToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun StarButton(onTap: () -> Unit, onLongPress: () -> Unit) {
    Text(
        text = "★",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    )
}

@Composable
fun BookmarksDialog(
    bookmarks: List<Int>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks.\nLong-press ★ to add the current page.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(bookmarks) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(p) {
                                    detectTapGestures(
                                        onTap = { onSelect(p) },
                                        onLongPress = { onRemove(p) }
                                    )
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = p.toString(),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "hold=remove",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * Prende qualunque stringa (OCR/map) e tira fuori SOLO una pagina valida 100..899.
 * Se non la trova -> null.
 */
private fun sanitizePage(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("""([1-8]\d{2})""").find(raw)
    val p = m?.groupValues?.getOrNull(1) ?: return null
    return p
}

@Composable
fun TelevideoImage(
    bitmap: Bitmap,
    clickAreas: List<ClickArea>,
    stretchY: Float,
    onTapArea: (ClickArea) -> Unit,
    debug: Boolean = false
) {
    if (bitmap.width <= 0 || bitmap.height <= 0) return

    // ------------------------------------------------------------
    // Correzione automatica della scala orizzontale (X)
    // k = 0.80 (come hai validato)
    // ------------------------------------------------------------
    val correctedAreas = remember(bitmap.width, clickAreas) {
        if (clickAreas.isEmpty()) return@remember emptyList<ClickArea>()

        val maxX = clickAreas.maxOf { it.x2 }.coerceAtLeast(1)
        val wBmp = bitmap.width
        val needScale = maxX < (wBmp * 0.75f)

        if (!needScale) {
            clickAreas
        } else {
            val targetScale = wBmp.toFloat() / maxX.toFloat()
            val k = 0.81f
            val scaleX = 1f + (targetScale - 1f) * k

            fun sx(x: Int): Int =
                (x * scaleX).roundToInt().coerceIn(0, wBmp)

            clickAreas.map { a ->
                ClickArea(
                    x1 = sx(a.x1),
                    y1 = a.y1,
                    x2 = sx(a.x2),
                    y2 = a.y2,
                    page = a.page,
                    subpage = a.subpage
                )
            }
        }
    }

    // ---- DEBUG STATE ----
    var lastTapView by remember { mutableStateOf<Offset?>(null) }
    var lastTapBmp by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastHits by remember { mutableStateOf<List<ClickArea>>(emptyList()) }
    var lastChosenRaw by remember { mutableStateOf<String?>(null) }
    var lastChosenClean by remember { mutableStateOf<String?>(null) }
    var lastChosenRect by remember { mutableStateOf<ClickArea?>(null) }

    val imgModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(bitmap.width.toFloat() / (bitmap.height.toFloat() * stretchY))
        .pointerInput(bitmap, correctedAreas, stretchY) {
            detectTapGestures { tap: Offset ->
                val vw = size.width
                val vh = size.height
                if (vw <= 0f || vh <= 0f) return@detectTapGestures

                val px = ((tap.x / vw) * bitmap.width)
                    .roundToInt()
                    .coerceIn(0, bitmap.width - 1)

                val py = ((tap.y / vh) * bitmap.height)
                    .roundToInt()
                    .coerceIn(0, bitmap.height - 1)

                val hits = correctedAreas.filter { it.contains(px, py) }

                val hit = hits.minByOrNull { a ->
                    ((a.x2 - a.x1).coerceAtLeast(1) * (a.y2 - a.y1).coerceAtLeast(1))
                }

                val rawPage = hit?.page
                val cleanPage = sanitizePage(rawPage)

                if (debug) {
                    lastTapView = tap
                    lastTapBmp = px to py
                    lastHits = hits
                    lastChosenRect = hit
                    lastChosenRaw = rawPage
                    lastChosenClean = cleanPage

                    println(
                        "TVDBG tapBmp=($px,$py) hits=${hits.size} pages=${hits.joinToString { it.page }} " +
                                "chosenRaw=$rawPage chosenClean=$cleanPage"
                    )
                }

                // 🚫 Se la pagina OCR è sporca/invalid -> NON navighiamo (evita crash)
                if (hit != null && cleanPage != null) {
                    val safeHit = hit.copy(page = cleanPage)
                    onTapArea(safeHit)
                }
            }
        }

    Box(modifier = imgModifier) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Televideo",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        if (debug && correctedAreas.isNotEmpty()) {
            val textSizePx = 56f

            val textPaintFill = Paint().apply {
                isAntiAlias = true
                textSize = textSizePx
                color = android.graphics.Color.WHITE
                style = Paint.Style.FILL
            }

            val textPaintStroke = Paint().apply {
                isAntiAlias = true
                textSize = textSizePx
                color = android.graphics.Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 7f
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val viewW = size.width
                val viewH = size.height
                val sx = viewW / bitmap.width.toFloat()
                val sy = viewH / bitmap.height.toFloat()

                correctedAreas.forEach { a ->
                    val x = a.x1 * sx
                    val y = a.y1 * sy
                    val ww = (a.x2 - a.x1) * sx
                    val hh = (a.y2 - a.y1) * sy
                    if (ww <= 0f || hh <= 0f) return@forEach

                    val isHit = lastHits.any { h ->
                        h.page == a.page &&
                                h.x1 == a.x1 && h.y1 == a.y1 &&
                                h.x2 == a.x2 && h.y2 == a.y2
                    }

                    drawRect(
                        color = if (isHit) Color.Green else Color.Red,
                        topLeft = Offset(x, y),
                        size = Size(ww, hh),
                        style = Stroke(width = if (isHit) 4f else 2f)
                    )

                    val tx = x + 6f
                    val ty = y + 58f
                    drawContext.canvas.nativeCanvas.drawText(a.page, tx, ty, textPaintStroke)
                    drawContext.canvas.nativeCanvas.drawText(a.page, tx, ty, textPaintFill)
                }

                // crosshair view
                lastTapView?.let { tv ->
                    drawLine(Color.Cyan, Offset(tv.x - 18f, tv.y), Offset(tv.x + 18f, tv.y), strokeWidth = 3f)
                    drawLine(Color.Cyan, Offset(tv.x, tv.y - 18f), Offset(tv.x, tv.y + 18f), strokeWidth = 3f)
                }

                // point bitmap->view
                lastTapBmp?.let { (bx, by) ->
                    val cx = bx * sx
                    val cy = by * sy
                    drawCircle(Color.Magenta, radius = 7f, center = Offset(cx, cy))
                }

                // rettangolo scelto evidenziato (giallo)
                lastChosenRect?.let { a ->
                    val x = a.x1 * sx
                    val y = a.y1 * sy
                    val ww = (a.x2 - a.x1) * sx
                    val hh = (a.y2 - a.y1) * sy
                    if (ww > 0f && hh > 0f) {
                        drawRect(
                            color = Color.Yellow,
                            topLeft = Offset(x, y),
                            size = Size(ww, hh),
                            style = Stroke(width = 6f)
                        )
                    }
                }
            }

            val info = buildString {
                val tv = lastTapView
                val tb = lastTapBmp
                append("bmp=${bitmap.width}x${bitmap.height}\n")
                append("tapView=")
                append(if (tv != null) "(${tv.x.roundToInt()},${tv.y.roundToInt()})" else "null")
                append("  tapBmp=")
                append(if (tb != null) "(${tb.first},${tb.second})" else "null")
                append("\n")
                append("hits=${lastHits.size}")
                if (lastHits.isNotEmpty()) {
                    append(" pages=")
                    append(lastHits.joinToString { it.page })
                }
                append("\nchosenRaw=")
                append(lastChosenRaw ?: "null")
                append("\nchosenClean=")
                append(lastChosenClean ?: "null")
            }

            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .background(Color(0x99000000))
                    .padding(6.dp)
            ) {
                Text(
                    text = info,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )

                if (lastChosenClean != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xCC000000))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "GO ${lastChosenClean}",
                            color = Color.Yellow,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp
                        )
                    }
                }
            }
        }
    }
}
