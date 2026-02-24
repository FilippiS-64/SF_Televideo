package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelevideoRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "TelevideoRepo"
        private const val TAG_NAV = "TV_NAV"

        private const val BASE_SITE = "https://www.servizitelevideo.rai.it/"
        private const val BASE_PNG = "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/"
    }

    // -----------------------------
    // HTTP helpers (UA + Accept + Referer) + cancellabile
    // -----------------------------
    private fun buildRequest(url: String, referer: String? = null): Request {
        return Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { try { cancel() } catch (_: Exception) {} }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    private suspend fun downloadBytes(url: String, referer: String? = null): ByteArray {
        val call = client.newCall(buildRequest(url, referer))
        val resp = call.awaitResponse()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} url=$url")
            return it.body?.bytes() ?: throw IOException("Empty body url=$url")
        }
    }

    private suspend fun downloadString(url: String, referer: String? = null): String {
        val call = client.newCall(buildRequest(url, referer))
        val resp = call.awaitResponse()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} url=$url")
            return it.body?.string().orEmpty()
        }
    }

    private fun is404(e: Exception): Boolean =
        (e.message ?: "").contains("HTTP 404", ignoreCase = true)

    // -----------------------------
    // Key parsing (accetta 102-02 / 102/2 / 102.2 / 102_02)
    // -----------------------------
    private fun parseKey(key: String): Pair<String, Int?> {
        val m = Regex("""^\s*([1-8]\d{2})(?:[-/._](\d{1,2}))?\s*$""").find(key.trim())
        if (m != null) {
            val page = m.groupValues[1]
            val sub = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            return page to sub
        }
        val digits = key.filter { it.isDigit() }
        val page = digits.take(3).ifBlank { "100" }
        val sub = if (digits.length >= 5) digits.substring(3, 5).toIntOrNull() else null
        return page to sub
    }

    // -----------------------------
    // URL builders
    // -----------------------------
    private fun candidatesBasePage(page: String): List<String> =
        listOf(
            "${BASE_PNG}16_9_page-$page.png",
            "${BASE_PNG}16_9_page-$page-01.png",
            "${BASE_PNG}16_9_page-${page}_01.png",
            "${BASE_PNG}16_9_page-$page.jpg",
            "${BASE_PNG}16_9_page-$page-01.jpg"
        ).distinct()

    private fun candidatesSubPage(page: String, sub: Int): List<String> {
        val sNoPad = sub.toString()
        val sPad2 = sub.toString().padStart(2, '0')
        return listOf(
            "${BASE_PNG}16_9_page-$page.$sNoPad.png",
            "${BASE_PNG}16_9_page-$page.$sPad2.png",
            "${BASE_PNG}16_9_page-$page-$sPad2.png",
            "${BASE_PNG}16_9_page-$page-$sNoPad.png",
            "${BASE_PNG}16_9_page-${page}_$sPad2.png",
            "${BASE_PNG}16_9_page-${page}_$sNoPad.png",
            "${BASE_PNG}16_9_page-${page}${sPad2}.png",
            "${BASE_PNG}16_9_page-$page.$sNoPad.jpg",
            "${BASE_PNG}16_9_page-$page-$sPad2.jpg",
            "${BASE_PNG}16_9_page-${page}_$sPad2.jpg",
            "${BASE_PNG}16_9_page-${page}${sPad2}.jpg"
        ).distinct()
    }

    private fun paginaLegacyUrl(page: String, sub: Int?): String {
        val base = "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp"
        return if (sub == null || sub <= 1) {
            "$base?pagina=$page"
        } else {
            "$base?pagina=$page&sottopagina=$sub"
        }
    }

    private fun paginaJspUrl(page: String, sub: Int?): String {
        val base = "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp"
        return if (sub == null || sub <= 1) {
            "$base?p=$page&r=Nazionale"
        } else {
            "$base?p=$page&s=$sub&r=Nazionale"
        }
    }

    private suspend fun fetchBitmapFromUrl(url: String, referer: String? = null): Bitmap {
        Log.d(TAG_NAV, "REPO try url=$url referer=${referer ?: "(none)"}")
        val bytes = downloadBytes(url, referer)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Decode bitmap failed url=$url")
    }

    private suspend fun fetchFirstWorkingBitmap(
        urls: List<String>,
        referers: List<String?> = listOf(null, BASE_SITE)
    ): Bitmap {
        var lastErr: Exception? = null

        for (u in urls.distinct()) {
            for (ref in referers) {
                try {
                    return fetchBitmapFromUrl(u, referer = ref)
                } catch (e: Exception) {
                    lastErr = e
                    if (is404(e)) {
                        Log.w(TAG_NAV, "REPO 404 on $u (ref=${ref ?: "none"})")
                        continue
                    }
                    throw e
                }
            }
        }
        throw IOException("Nessun URL funzionante. Ultimo errore: ${lastErr?.message}", lastErr)
    }

    // -----------------------------
    // Parsing HTML -> URL immagini
    // -----------------------------
    private fun extractAllImageUrls(html: String, baseUri: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, baseUri)
        val out = linkedSetOf<String>()

        fun add(u: String?) {
            val x = u?.trim().orEmpty()
            if (x.isNotBlank()) out.add(x)
        }

        for (img in doc.select("img")) {
            add(img.absUrl("src").takeIf { it.isNotBlank() })
            add(img.absUrl("data-src").takeIf { it.isNotBlank() })
            add(img.absUrl("data-original").takeIf { it.isNotBlank() })
        }

        val rxBg = Regex("""url\((['"]?)(https?://[^'")\s>]+)\1\)""", RegexOption.IGNORE_CASE)
        for (el in doc.select("[style]")) {
            val style = el.attr("style")
            rxBg.findAll(style).forEach { m -> add(m.groupValues[2]) }
        }

        val rxAny = Regex("""https?://[^\s"'<>]+?\.(png|jpg|jpeg)""", RegexOption.IGNORE_CASE)
        rxAny.findAll(html).forEach { m -> add(m.value) }

        return out.toList()
    }

    private suspend fun resolveAllImageUrlsFromPagina(url: String): List<String> {
        val html = downloadString(url, referer = BASE_SITE)
        return extractAllImageUrls(html, BASE_SITE)
    }

    /**
     * 🔥 FILTRO DURO: tieni SOLO immagini che sembrano davvero TELETEXT:
     * - path contiene /tt4web/
     * - filename contiene 16_9_page-
     * - contiene la pagina richiesta
     * - se sub>1: contiene anche l'indicatore della sub (dot/dash/underscore/concat)
     * Inoltre per sub>1 NON includere MAI la base 16_9_page-102.png come candidata.
     */
    private fun filterTeletextUrlsFor(page: String, sub: Int, urls: List<String>): List<String> {
        if (urls.isEmpty()) return emptyList()

        val p = page.lowercase()
        val sNo = sub.toString()
        val s2 = sub.toString().padStart(2, '0')

        val basePng = "${BASE_PNG}16_9_page-$page.png".lowercase()
        val baseJpg = "${BASE_PNG}16_9_page-$page.jpg".lowercase()

        fun looksLikeTeletext(u: String): Boolean {
            val x = u.lowercase()
            return x.contains("/tt4web/") &&
                    x.contains("16_9_page-") &&
                    x.contains("16_9_page-$p")
        }

        fun looksLikeSub(u: String): Boolean {
            val x = u.lowercase()
            return x.contains("16_9_page-$p.$sNo") ||
                    x.contains("16_9_page-$p.$s2") ||
                    x.contains("16_9_page-$p-$s2") ||
                    x.contains("16_9_page-$p-$sNo") ||
                    x.contains("16_9_page-${p}_$s2") ||
                    x.contains("16_9_page-${p}_$sNo") ||
                    x.contains("16_9_page-${p}${s2}")
        }

        fun isBase(u: String): Boolean {
            val x = u.lowercase()
            return x == basePng || x == baseJpg
        }

        val teletext = urls.filter { looksLikeTeletext(it) }

        return if (sub > 1) {
            // solo sub vere; la base è proibita
            val subs = teletext.filter { looksLikeSub(it) }.distinct()
            subs
        } else {
            // base: tutte teletext ok (anche base)
            val filtered = teletext.filterNot { false }.distinct()
            filtered
        }
    }

    suspend fun fetchBitmap(key: String): Bitmap =
        withContext(Dispatchers.IO) {
            val (page, subRaw) = parseKey(key)
            val sub = subRaw ?: 1

            Log.d(TAG_NAV, "REPO fetchBitmap key='$key' parsed page=$page sub=$sub")

            // -----------------------------
            // SUBPAGES (sub > 1)
            // -----------------------------
            if (sub > 1) {
                val legacyUrl = paginaLegacyUrl(page, sub)

                // 0) legacy -> estrai URL -> FILTRA SOLO SUBPAGE REALI TELETEXT
                try {
                    Log.d(TAG_NAV, "REPO sub>1 -> legacy $legacyUrl")
                    val resolved = resolveAllImageUrlsFromPagina(legacyUrl)
                    val filtered = filterTeletextUrlsFor(page, sub, resolved)

                    Log.d(TAG_NAV, "REPO sub>1 legacy resolved=${resolved.size} filtered=${filtered.size}")

                    if (filtered.isNotEmpty()) {
                        return@withContext fetchFirstWorkingBitmap(
                            urls = filtered,
                            referers = listOf(legacyUrl, BASE_SITE, null)
                        )
                    } else {
                        Log.w(TAG_NAV, "REPO sub>1 legacy: no valid tt4web subpage urls found")
                    }
                } catch (e: Exception) {
                    Log.w(TAG_NAV, "REPO sub>1 legacy FAIL err=${e.message}")
                }

                // 1) varianti dirette tt4web
                try {
                    return@withContext fetchFirstWorkingBitmap(
                        urls = candidatesSubPage(page, sub),
                        referers = listOf(legacyUrl, BASE_SITE, null)
                    )
                } catch (e: Exception) {
                    if (!is404(e)) throw e
                }

                // 2) fallback pagina.jsp nuova -> FILTRA
                val paginaUrl = paginaJspUrl(page, sub)
                Log.d(TAG_NAV, "REPO sub>1 -> pagina.jsp $paginaUrl")

                val resolvedAll = resolveAllImageUrlsFromPagina(paginaUrl)
                val filteredAll = filterTeletextUrlsFor(page, sub, resolvedAll)

                Log.d(TAG_NAV, "REPO sub>1 pagina.jsp resolved=${resolvedAll.size} filtered=${filteredAll.size}")

                if (filteredAll.isEmpty()) {
                    throw IOException("Pagina $key non disponibile (nessun URL subpage tt4web trovato)")
                }

                return@withContext fetchFirstWorkingBitmap(
                    urls = filteredAll,
                    referers = listOf(paginaUrl, BASE_SITE, null)
                )
            }

            // -----------------------------
            // BASE PAGE (sub <= 1)
            // -----------------------------
            try {
                return@withContext fetchFirstWorkingBitmap(
                    urls = candidatesBasePage(page),
                    referers = listOf(BASE_SITE, null)
                )
            } catch (e: Exception) {
                if (!is404(e)) throw e
            }

            // fallback legacy
            val legacyUrl = paginaLegacyUrl(page, 1)
            try {
                Log.d(TAG_NAV, "REPO base -> legacy $legacyUrl")
                val resolved = resolveAllImageUrlsFromPagina(legacyUrl)
                val filtered = filterTeletextUrlsFor(page, 1, resolved)

                Log.d(TAG_NAV, "REPO base legacy resolved=${resolved.size} filtered=${filtered.size}")

                if (filtered.isNotEmpty()) {
                    return@withContext fetchFirstWorkingBitmap(
                        urls = filtered,
                        referers = listOf(legacyUrl, BASE_SITE, null)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG_NAV, "REPO base legacy FAIL err=${e.message}")
                if (!is404(e)) throw e
            }

            // fallback finale pagina.jsp nuova
            val paginaUrl = paginaJspUrl(page, 1)
            Log.d(TAG_NAV, "REPO base -> pagina.jsp $paginaUrl")

            val resolvedAll = resolveAllImageUrlsFromPagina(paginaUrl)
            val filteredAll = filterTeletextUrlsFor(page, 1, resolvedAll)

            Log.d(TAG_NAV, "REPO base pagina.jsp resolved=${resolvedAll.size} filtered=${filteredAll.size}")

            if (filteredAll.isEmpty()) {
                throw IOException("Pagina $key non disponibile (nessun URL tt4web trovato)")
            }

            return@withContext fetchFirstWorkingBitmap(
                urls = filteredAll,
                referers = listOf(paginaUrl, BASE_SITE, null)
            )
        }

    // ----------------------------------------------------------
    // CLICK AREAS (MAP) — invariato
    // ----------------------------------------------------------
    fun mapUrl(page: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

    suspend fun fetchClickAreas(page: String): List<ClickArea> =
        withContext(Dispatchers.IO) {
            val url = mapUrl(page)
            try {
                val html = downloadString(url, referer = BASE_SITE)
                if (html.isBlank()) return@withContext emptyList()

                val doc = Jsoup.parse(html, BASE_SITE)
                val areas = doc.select("area[coords][href]")
                if (areas.isEmpty()) return@withContext emptyList()

                val out = mutableListOf<ClickArea>()

                val rxPageParam = Pattern.compile("""(?:\bpagina\b|\bp\b)\s*=\s*([1-8]\d{2})""")
                val rxAnyPage = Pattern.compile("""\b([1-8]\d{2})\b""")
                val rxSub = Pattern.compile("""(?:\bsottopagina\b|\bs\b)\s*=\s*(\d{1,2})""")

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

                    var subStr: String? = null
                    val mS = rxSub.matcher(href)
                    while (mS.find()) subStr = mS.group(1)

                    val finalPage = pageNum ?: continue

                    out.add(
                        ClickArea(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            page = finalPage,
                            subpage = subStr?.padStart(2, '0')
                        )
                    )
                }

                out
            } catch (e: Exception) {
                Log.e(TAG, "fetchClickAreas FAIL page=$page url=$url", e)
                emptyList()
            }
        }

    // ----------------------------------------------------------
    // OCR (come tuo)
    // ----------------------------------------------------------
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

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