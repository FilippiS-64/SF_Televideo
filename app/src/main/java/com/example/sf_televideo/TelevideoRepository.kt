package com.example.sf_televideo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.regex.Pattern

class TelevideoRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun imageUrl(page: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/tt4web/Nazionale/16_9_page-$page.png"

    fun mapUrl(page: String): String =
        "https://www.servizitelevideo.rai.it/televideo/pub/pagina.jsp?pagina=$page"

    suspend fun fetchBitmap(page: String): Bitmap =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url(imageUrl(page)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: throw IOException("Empty image body")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Decode bitmap failed")
            }
        }

    suspend fun fetchClickAreas(page: String): List<ClickArea> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url(mapUrl(page)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()

                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html, "https://www.servizitelevideo.rai.it/")

                val areas = doc.select("area[coords][href]")
                if (areas.isEmpty()) return@withContext emptyList()

                val out = mutableListOf<ClickArea>()
                val rxPage = Pattern.compile("(?:\\?|&)(?:pagina|p)=(\\d{3})")
                val rxSub = Pattern.compile("(?:\\?|&)(?:sottopagina|s)=(\\d{1,2})")

                for (a in areas) {
                    val coordsRaw = a.attr("coords").trim()
                    val href = a.absUrl("href").ifBlank { a.attr("href") }

                    val coords = coordsRaw.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (coords.size < 4) continue

                    val x1 = minOf(coords[0], coords[2])
                    val y1 = minOf(coords[1], coords[3])
                    val x2 = maxOf(coords[0], coords[2])
                    val y2 = maxOf(coords[1], coords[3])

                    val mP = rxPage.matcher(href)
                    var p: String? = null
                    while (mP.find()) p = mP.group(1)

                    val mS = rxSub.matcher(href)
                    var s: String? = null
                    while (mS.find()) s = mS.group(1)

                    val pageNum = p ?: continue
                    out.add(
                        ClickArea(
                            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                            page = pageNum,
                            subpage = s?.padStart(2, '0')
                        )
                    )
                }
                out
            }
        }
}
