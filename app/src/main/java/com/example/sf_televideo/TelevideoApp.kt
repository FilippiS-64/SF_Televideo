@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

// ---------- DataStore ----------
private val Context.dataStore by preferencesDataStore(name = "sf_televideo_prefs")
private val BOOKMARKS_KEY = stringSetPreferencesKey("bookmarks_pages")

@Composable
fun TelevideoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TelevideoRepository() }

    var currentPage by remember { mutableStateOf("100") }      // sempre 3 cifre
    var currentSubpage by remember { mutableStateOf("01") }    // sempre 2 cifre
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var clickAreas by remember { mutableStateOf<List<ClickArea>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val bookmarks = remember { mutableStateListOf<Int>() }
    var showBookmarks by remember { mutableStateOf(false) }

    // ✅ per evitare load sovrapposti (swipe veloce)
    var loadJob by remember { mutableStateOf<Job?>(null) }

    // ---- Load bookmarks once at startup ----
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        val saved = prefs[BOOKMARKS_KEY].orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .sorted()
        bookmarks.clear()
        bookmarks.addAll(saved)
    }

    fun persistBookmarks() {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[BOOKMARKS_KEY] = bookmarks.map { it.toString() }.toSet()
            }
        }
    }

    /**
     * Accetta input in questi formati:
     *  - "261"       -> page=261 sub=01
     *  - "26102"     -> page=261 sub=02
     *  - "261-02"    -> page=261 sub=02
     *  - "261/02"    -> page=261 sub=02
     *  - "pagina=261&sottopagina=2" -> estrae 261 e 02
     */
    fun parsePageAndSub(input: String): Pair<String, String>? {
        val cleaned = input.trim()

        val sepMatch = Regex("""^\s*([1-8]\d{2})\s*[-/]\s*(\d{1,2})\s*$""").find(cleaned)
        if (sepMatch != null) {
            val pageStr = sepMatch.groupValues[1]
            val subInt = sepMatch.groupValues[2].toIntOrNull() ?: return null
            val pageInt = pageStr.toIntOrNull() ?: return null
            if (pageInt !in 100..899) return null
            if (subInt !in 1..99) return null
            return pageStr to subInt.toString().padStart(2, '0')
        }

        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 3) return null

        val pageStr = digits.substring(0, 3)
        val pageInt = pageStr.toIntOrNull() ?: return null
        if (pageInt !in 100..899) return null

        val subRaw = if (digits.length >= 5) digits.substring(3, 5) else "01"
        val subInt = subRaw.toIntOrNull() ?: return null
        if (subInt !in 1..99) return null

        return pageStr to subInt.toString().padStart(2, '0')
    }

    /**
     * Carica pagina+sub:
     * - per sub=01: usa "261"
     * - per sub!=01: usa "261-02"
     *
     * ✅ FIX: cancella eventuale load precedente e non crasha mai.
     */
    fun load(input: String) {
        val parsed = parsePageAndSub(input) ?: return
        val (page, sub) = parsed

        // ✅ stop load precedente se fai swipe rapido
        loadJob?.cancel()

        loadJob = scope.launch {
            isLoading = true
            errorText = null

            // aggiorna SUBITO lo stato richiesto (UI)
            currentPage = page
            currentSubpage = sub

            clickAreas = emptyList()

            try {
                val keyForBitmap = if (sub == "01") page else "$page-$sub"
                Log.d("TVDBG", "LOAD request input=$input  -> page=$page sub=$sub  keyForBitmap=$keyForBitmap")

                // ✅ supervisorScope: se uno dei due fallisce, non ammazza tutto
                supervisorScope {
                    val bmpDeferred = async(Dispatchers.IO) {
                        repo.fetchBitmap(keyForBitmap)   // può fallire -> gestito da await
                    }

                    val areasDeferred = async(Dispatchers.IO) {
                        // aree sempre su "page" (senza sub)
                        repo.fetchClickAreas(page)
                    }

                    // bitmap: se fallisce -> va in catch sotto e NON crasha
                    bitmap = bmpDeferred.await()

                    // clickAreas: se fallisce, restano vuote
                    clickAreas = try {
                        areasDeferred.await()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } catch (ce: CancellationException) {
                // normale quando swipe rapido: ignorare
                Log.d("TVDBG", "LOAD cancelled for input=$input")
            } catch (e: Exception) {
                Log.e("TVDBG", "LOAD failed input=$input", e)
                bitmap = null
                clickAreas = emptyList()
                errorText = e.message ?: "Unknown error"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { load("100") }

    TelevideoScreen(
        currentPage = currentPage,
        currentSubpage = currentSubpage,
        bitmap = bitmap,
        clickAreas = clickAreas,
        isLoading = isLoading,
        errorText = errorText,
        bookmarks = bookmarks,
        showBookmarks = showBookmarks,
        onShowBookmarksChange = { showBookmarks = it },
        onLoadPage = { load(it) },
        onAddBookmark = { pageStr ->
            Log.d("BOOKMARKS", "ADD requested pageStr=$pageStr | current=$currentPage sub=$currentSubpage")
            val n = pageStr.toIntOrNull() ?: return@TelevideoScreen
            if (n !in 100..899) return@TelevideoScreen
            if (!bookmarks.contains(n)) {
                bookmarks.add(n)
                bookmarks.sort()
                persistBookmarks()
            }
        },
        onRemoveBookmark = { n ->
            if (bookmarks.remove(n)) {
                persistBookmarks()
            }
        }
    )
}