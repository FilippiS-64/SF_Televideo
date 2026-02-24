package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext

class TelevideoRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "TelevideoRepo"
        private const val TAG_BMP = "BMP"
    }

    // key può essere "102" oppure "102-02"
    fun imageUrl(key: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$key.png"

    // pagina.jsp: per le sottopagine usa i parametri p e s (come l'URL che hai verificato tu)
    fun paginaJspUrl(page: String, sub: Int?): String {
        val base = "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp"
        return if (sub == null || sub <= 1) {
            "$base?p=$page&r=Nazionale"
        } else {
            "$base?p=$page&s=$sub&r=Nazionale"
        }
    }

    // per clickAreas manteniamo il vecchio map “semplice”
    fun mapUrl(page: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

    // ✅ Reuse del recognizer
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun parseKey(key: String): Pair<String, Int?> {
        val m = Regex("""^\s*([1-8]\d{2})(?:[-/](\d{1,2}))?\s*$""").find(key.trim())
        if (m != null) {
            val p = m.groupValues[1]
            val s = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            return p to s
        }
        // fallback: prendi solo cifre
        val digits = key.filter { it.isDigit() }
        if (digits.length < 3) return "100" to null // fallback ultra difensivo
        val p = digits.take(3)
        val s = if (digits.length >= 5) digits.substring(3, 5).toIntOrNull() else null
        return p to s
    }

    // -----------------------------
    // ✅ OkHttp cancellabile
    // -----------------------------
    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                try { cancel() } catch (_: Exception) {}
            }
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private suspend fun callAwaitBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)

        val resp = try {
            call.awaitResponse()
        } catch (ce: CancellationException) {
            Log.d(TAG_BMP, "call cancelled url=$url")
            throw ce
        }

        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} url=$url")
            val bytes = r.body?.bytes() ?: throw IOException("Empty body url=$url")
            return bytes
        }
    }

    private suspend fun callAwaitString(url: String): String {
        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)

        val resp = try {
            call.awaitResponse()
        } catch (ce: CancellationException) {
            Log.d(TAG, "call cancelled url=$url")
            throw ce
        }

        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} url=$url")
            return r.body?.string().orEmpty()
        }
    }

    /**
     * Bitmap fetch robusta:
     * 1) prova URL PNG standard (tt4web)
     * 2) se 404 -> scarica pagina.jsp (con p/s corretti) e trova l'IMG reale -> scarica quella
     */
    suspend fun fetchBitmap(key: String): Bitmap =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()

            val primaryUrl = imageUrl(key)
            Log.d(TAG_BMP, "fetchBitmap(key=$key) try direct=$primaryUrl")

            try {
                fetchBitmapFromUrl(primaryUrl)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()

                val msg = e.message.orEmpty()
                val is404 = msg.contains("HTTP 404", ignoreCase = true)

                if (!is404) {
                    Log.e(TAG, "fetchBitmap primary FAIL key=$key url=$primaryUrl", e)
                    throw e
                }

                // ✅ fallback: pagina.jsp con sub corretta
                val (page, sub) = parseKey(key)
                val paginaUrl = paginaJspUrl(page, sub)

                Log.w(TAG, "Direct PNG failed for key=$key url=$primaryUrl err=$msg")
                Log.w(TAG, "Trying fallback via pagina.jsp: $paginaUrl")

                val fallbackImageUrl = try {
                    resolveImageUrlFromPaginaJsp(paginaUrl)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback resolveImageUrlFromPaginaJsp FAIL key=$key paginaUrl=$paginaUrl", ex)
                    null
                }

                Log.d(TAG_BMP, "fallbackImageUrl key=$key -> $fallbackImageUrl")

                if (fallbackImageUrl.isNullOrBlank()) {
                    throw IOException("Pagina $key non disponibile (immagine non trovata)")
                }

                try {
                    fetchBitmapFromUrl(fallbackImageUrl)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    Log.e(TAG, "fetchBitmap fallback FAIL key=$key url=$fallbackImageUrl", ex)
                    throw IOException("Pagina $key non disponibile (errore download immagine)")
                }
            }
        }

    private suspend fun fetchBitmapFromUrl(url: String): Bitmap {
        currentCoroutineContext().ensureActive()

        Log.d(TAG_BMP, "fetchBitmapFromUrl url=$url")
        val bytes = callAwaitBytes(url)

        currentCoroutineContext().ensureActive()

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Decode bitmap failed url=$url")
    }

    /**
     * Legge pagina.jsp (già costruita con p/s) e prova a ricavare l'URL dell'immagine reale.
     */
    private suspend fun resolveImageUrlFromPaginaJsp(paginaUrl: String): String? {
        currentCoroutineContext().ensureActive()

        val html = callAwaitString(paginaUrl)
        if (html.isBlank()) return null

        currentCoroutineContext().ensureActive()

        val doc = Jsoup.parse(html, "https://www.servizitelevideo.rai.it/")

        // 1) prima immagine png in pagina
        val img1 = doc.selectFirst("img[src$=.png]")
        val abs1 = img1?.absUrl("src")?.takeIf { it.isNotBlank() }
        if (!abs1.isNullOrBlank()) return abs1

        // 2) qualunque img con ".png"
        val imgs = doc.select("img[src]")
        for (img in imgs) {
            val src = img.attr("src")
            if (src.contains(".png", ignoreCase = true)) {
                val abs = img.absUrl("src")
                if (abs.isNotBlank()) return abs
            }
        }

        // 3) regex dentro html
        val rx = Regex("""https?://[^\s"'<>]+\.png""", RegexOption.IGNORE_CASE)
        return rx.find(html)?.value
    }

    /**
     * MAP HTML (<area>) — non deve crashare
     */
    suspend fun fetchClickAreas(page: String): List<ClickArea> =
        withContext(Dispatchers.IO) {
            val url = mapUrl(page)
            try {
                currentCoroutineContext().ensureActive()

                val html = callAwaitString(url)
                if (html.isBlank()) return@withContext emptyList()

                currentCoroutineContext().ensureActive()

                val doc = Jsoup.parse(html, "https://www.servizitelevideo.rai.it/")

                val areas = doc.select("area[coords][href]")
                if (areas.isEmpty()) return@withContext emptyList()

                val out = mutableListOf<ClickArea>()

                val rxPageParam =
                    Pattern.compile("""(?:\bpagina\b|\bp\b)\s*=\s*([1-8]\d{2})""")
                val rxAnyPage =
                    Pattern.compile("""\b([1-8]\d{2})\b""")
                val rxSub =
                    Pattern.compile("""(?:\bsottopagina\b|\bs\b)\s*=\s*(\d{1,2})""")

                for (a in areas) {
                    val coordsRaw = a.attr("coords").trim()
                    val href = a.absUrl("href").ifBlank { a.attr("href") }

                    val coords = coordsRaw.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (coords.size < 4) continue

                    val x1 = minOf(coords[0], coords[2])
                    val y1 = minOf(coords[1], coords[3])
                    val x2 = maxOf(coords[0], coords[2])
                    val y2 = maxOf(coords[1], coords[3])

                    var pageNum: String? = null
                    val mP = rxPageParam.matcher(href)
                    while (mP.find()) pageNum = mP.group(1)

                    if (pageNum == null) {
                        val mAny = rxAnyPage.matcher(href)
                        while (mAny.find()) pageNum = mAny.group(1)
                    }

                    var sub: String? = null
                    val mS = rxSub.matcher(href)
                    while (mS.find()) sub = mS.group(1)

                    val finalPage = pageNum ?: continue

                    out.add(
                        ClickArea(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            page = finalPage,
                            subpage = sub?.padStart(2, '0')
                        )
                    )
                }
                out
            } catch (ce: CancellationException) {
                // normale su swipe rapidi
                Log.d(TAG, "fetchClickAreas CANCELLED page=$page")
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "fetchClickAreas(MAP) FAIL page=$page url=$url", e)
                emptyList()
            }
        }

    /**
     * OCR+MAP combinati — non deve crashare
     */
    suspend fun fetchClickAreas(page: String, bitmap: Bitmap): List<ClickArea> =
        withContext(Dispatchers.Default) {
            val fromMap = try { fetchClickAreas(page) } catch (_: Exception) { emptyList() }
            if (fromMap.size >= 5) return@withContext fromMap

            val fromOcr = try { fetchClickAreasOcr(bitmap) } catch (_: Exception) { emptyList() }
            if (fromOcr.isNotEmpty()) fromOcr else fromMap
        }

    private val tokenPageRegex = Regex("""^[1-8]\d{2}$""")
    private val pageRegexInLine = Regex("""\b([1-8]\d{2})\b""")

    private suspend fun fetchClickAreasOcr(bitmap: Bitmap): List<ClickArea> =
        withContext(Dispatchers.Default) {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val out = mutableListOf<ClickArea>()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    var addedFromElements = false

                    for (el in line.elements) {
                        val t = el.text?.trim() ?: continue
                        if (!tokenPageRegex.matches(t)) continue
                        val b = el.boundingBox ?: continue

                        out.add(
                            ClickArea(
                                x1 = b.left,
                                y1 = b.top,
                                x2 = b.right,
                                y2 = b.bottom,
                                page = t,
                                subpage = null
                            )
                        )
                        addedFromElements = true
                    }

                    if (!addedFromElements) {
                        val lineText = line.text ?: continue
                        val box = line.boundingBox ?: continue
                        out += splitLineBoxIntoPageAreas(lineText, box)
                    }
                }
            }

            out.distinctBy { "${it.page}:${it.x1}:${it.y1}:${it.x2}:${it.y2}" }
        }

    private fun splitLineBoxIntoPageAreas(lineText: String, box: Rect): List<ClickArea> {
        val matches = pageRegexInLine.findAll(lineText).toList()
        if (matches.isEmpty()) return emptyList()

        val totalChars = lineText.length.coerceAtLeast(1)
        val width = (box.right - box.left).coerceAtLeast(1)

        return matches.map { m ->
            val page = m.groupValues[1]
            val start = m.range.first
            val endExclusive = m.range.last + 1

            val x1 = box.left + (width * (start.toFloat() / totalChars)).toInt()
            val x2 = box.left + (width * (endExclusive.toFloat() / totalChars)).toInt()

            ClickArea(
                x1 = x1,
                y1 = box.top,
                x2 = x2,
                y2 = box.bottom,
                page = page,
                subpage = null
            )
        }
    }
}