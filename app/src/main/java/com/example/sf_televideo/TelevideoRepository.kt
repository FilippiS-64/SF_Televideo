// TelevideoRepository.kt
package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern

class TelevideoRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "TelevideoRepo"
    }

    // Pagina "grafica" che contiene anche l'IMG reale
    // (tu hai verificato che questo URL funziona e mostra la subpage corretta)
    private fun paginaJspUrl(page: String, subInt: Int?): String {
        return if (subInt == null) {
            // pagina base (compatibile col tuo vecchio mapUrl)
            "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"
        } else {
            // versione con p/s (molto più affidabile per le sottopagine)
            // tieni pagetocall=pagina.jsp come nel link che hai testato
            val s = subInt.toString()
            "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?p=$page&s=$s&r=Nazionale&pagetocall=pagina.jsp"
        }
    }

    // MAP HTML (<area>)
    fun mapUrl(page: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

    // ✅ Reuse del recognizer
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * pageKey può essere:
     * - "102"      -> page=102 sub=null (o sub=01 gestita a monte)
     * - "102-08"   -> page=102 sub=8
     * - "10208"    -> page=102 sub=8
     * - "pagina=102&sottopagina=8" -> page=102 sub=8
     */
    private fun parsePageKey(pageKey: String): Pair<String, Int?>? {
        val cleaned = pageKey.trim()

        // caso "102-08" o "102/08"
        val sepMatch = Regex("""^\s*([1-8]\d{2})\s*[-/]\s*(\d{1,2})\s*$""").find(cleaned)
        if (sepMatch != null) {
            val p = sepMatch.groupValues[1]
            val s = sepMatch.groupValues[2].toIntOrNull() ?: return null
            if (s !in 1..99) return null
            return p to s
        }

        // fallback: solo cifre (supporta "10208", querystring, ecc.)
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 3) return null
        val page = digits.substring(0, 3)
        val pageInt = page.toIntOrNull() ?: return null
        if (pageInt !in 100..899) return null

        val sub: Int? = if (digits.length >= 5) {
            digits.substring(3, 5).toIntOrNull()?.takeIf { it in 1..99 }
        } else {
            null
        }

        return page to sub
    }

    /**
     * Fetch bitmap ROBUSTA:
     * - Se pageKey è una pagina base (sub=null) prova PNG diretta classica, poi fallback su pagina.jsp
     * - Se pageKey include subpage (sub!=null) NON proviamo mille pattern a caso:
     *   andiamo direttamente su pagina.jsp?p=XXX&s=Y e estraiamo l'IMG reale (src png) e la scarichiamo.
     */
    suspend fun fetchBitmap(pageKey: String): Bitmap =
        withContext(Dispatchers.IO) {
            val parsed = parsePageKey(pageKey)
                ?: throw IOException("pageKey non valido: $pageKey")

            val page = parsed.first
            val sub = parsed.second

            Log.d("BMP", "fetchBitmap(pageKey=$pageKey) parsed page=$page sub=$sub")

            // 1) Se NON è sottopagina, prova la PNG "classica"
            if (sub == null) {
                val primaryUrl =
                    "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$page.png"

                Log.d("BMP", "fetchBitmap(pageKey=$pageKey) try direct=$primaryUrl")

                try {
                    return@withContext fetchBitmapFromUrl(primaryUrl)
                } catch (e: IOException) {
                    Log.w(TAG, "Direct PNG failed for $pageKey url=$primaryUrl err=${e.message}")
                    // fallback sotto
                }
            }

            // 2) Fallback (o caso subpage): passa SEMPRE da pagina.jsp e ricava l'IMG reale
            val paginaUrl = paginaJspUrl(page, sub)
            Log.d("BMP", "fetchBitmap(pageKey=$pageKey) resolve via pagina.jsp url=$paginaUrl")

            val resolvedImgUrl = resolveImageUrlFromPaginaJsp(paginaUrl)
            Log.d("BMP", "fetchBitmap(pageKey=$pageKey) resolvedImgUrl=$resolvedImgUrl")

            if (resolvedImgUrl.isNullOrBlank()) {
                // messaggio esplicito (così in UI vedi cosa non va)
                throw IOException("Pagina $pageKey non disponibile (immagine non trovata in pagina.jsp)")
            }

            try {
                return@withContext fetchBitmapFromUrl(resolvedImgUrl)
            } catch (e: Exception) {
                Log.e(TAG, "fetchBitmap resolved FAIL pageKey=$pageKey url=$resolvedImgUrl", e)
                throw IOException("Pagina $pageKey non disponibile (errore download immagine)")
            }
        }

    private fun fetchBitmapFromUrl(url: String): Bitmap {
        Log.d("BMP", "fetchBitmapFromUrl url=$url")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} url=$url")
            val bytes = resp.body?.bytes() ?: throw IOException("Empty image body url=$url")
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("Decode bitmap failed url=$url")
        }
    }

    /**
     * Scarica l'HTML e prova a prendere l'IMG PNG reale.
     * Funziona sia per pagina.jsp?pagina=XXX sia per pagina.jsp?p=XXX&s=Y...
     */
    private fun resolveImageUrlFromPaginaJsp(paginaUrl: String): String? {
        val req = Request.Builder().url(paginaUrl).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return null

            val doc = Jsoup.parse(html, "https://www.servizitelevideo.rai.it/")

            // 1) prima scelta: prima img png
            val img1 = doc.selectFirst("img[src$=.png], img[src*=.png]")
            val abs1 = img1?.absUrl("src")?.takeIf { it.isNotBlank() }
            if (!abs1.isNullOrBlank()) return abs1

            // 2) cerca qualunque img con ".png"
            val imgs = doc.select("img[src]")
            for (img in imgs) {
                val src = img.attr("src")
                if (src.contains(".png", ignoreCase = true)) {
                    val abs = img.absUrl("src")
                    if (abs.isNotBlank()) return abs
                }
            }

            // 3) fallback regex dentro html
            val rx = Regex("""https?://[^\s"'<>]+\.png""", RegexOption.IGNORE_CASE)
            return rx.find(html)?.value
        }
    }

    /**
     * MAP HTML (<area>) — non deve crashare
     */
    suspend fun fetchClickAreas(page: String): List<ClickArea> =
        withContext(Dispatchers.IO) {
            val url = mapUrl(page)
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()

                    val html = resp.body?.string().orEmpty()
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
                }
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