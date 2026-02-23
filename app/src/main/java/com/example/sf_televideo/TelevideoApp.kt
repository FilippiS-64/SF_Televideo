@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    // ✅ Splash help: SEMPRE ad ogni avvio app
    var showHelp by rememberSaveable { mutableStateOf(true) }

    // ✅ job corrente per evitare load paralleli (crash su swipe rapidi)
    var loadJob by remember { mutableStateOf<Job?>(null) }

    // ✅ History per UNDO (salviamo la "chiave" usata per caricare bitmap: "261" oppure "261-02")
    val history = remember { mutableStateListOf<String>() }
    var isUndoing by remember { mutableStateOf(false) }

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

        // Caso "261-02" o "261/02"
        val sepMatch = Regex("""^\s*([1-8]\d{2})\s*[-/]\s*(\d{1,2})\s*$""").find(cleaned)
        if (sepMatch != null) {
            val pageStr = sepMatch.groupValues[1]
            val subInt = sepMatch.groupValues[2].toIntOrNull() ?: return null
            val pageInt = pageStr.toIntOrNull() ?: return null
            if (pageInt !in 100..899) return null
            if (subInt !in 1..99) return null
            return pageStr to subInt.toString().padStart(2, '0')
        }

        // Fallback: prendo solo cifre
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

    fun keyFor(page: String, sub: String): String =
        if (sub == "01") page else "$page-$sub"

    /**
     * Carica pagina+sub.
     * pushToHistory = true => salva lo stato precedente per UNDO
     */
    fun load(input: String, pushToHistory: Boolean = true) {
        val parsed = parsePageAndSub(input) ?: return
        val (page, sub) = parsed

        // ✅ cancella eventuale load precedente (swipe rapidi)
        loadJob?.cancel()

        val newKey = keyFor(page, sub)
        val prevKey = keyFor(currentPage, currentSubpage)

        // ✅ push history SOLO se:
        // - non siamo in undo
        // - pushToHistory true
        // - stiamo davvero cambiando pagina/sub
        if (!isUndoing && pushToHistory && newKey != prevKey) {
            history.add(prevKey)
            if (history.size > 80) history.removeAt(0) // cap per sicurezza
            Log.d("TVDBG", "HISTORY push $prevKey  (size=${history.size})")
        }

        loadJob = scope.launch {
            isLoading = true
            errorText = null

            // aggiorna SUBITO lo stato richiesto
            currentPage = page
            currentSubpage = sub

            // reset aree
            clickAreas = emptyList()

            try {
                Log.d("TVDBG", "LOAD request input=$input  -> page=$page sub=$sub  key=$newKey")

                coroutineScope {
                    val bmpDeferred = async(Dispatchers.IO) { repo.fetchBitmap(newKey) }
                    val areasDeferred = async(Dispatchers.IO) { repo.fetchClickAreas(page) }

                    bitmap = bmpDeferred.await()

                    clickAreas = try {
                        areasDeferred.await()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                errorText = e.message ?: "Unknown error"
                Log.e("TVDBG", "LOAD error: ${e.message}", e)
            } finally {
                isLoading = false
                isUndoing = false
            }
        }
    }

    fun undo() {
        if (history.isEmpty()) {
            Log.d("TVDBG", "UNDO: history empty")
            return
        }
        val prev = history.removeAt(history.lastIndex)
        Log.d("TVDBG", "UNDO -> $prev (remaining=${history.size})")

        isUndoing = true
        load(prev, pushToHistory = false)
    }

    // ----------------------------
    // ✅ Helpers per swipe ciclici
    // ----------------------------
    fun fmtPage(p: Int): String = p.toString().padStart(3, '0')
    fun fmtSub(s: Int): String = s.toString().padStart(2, '0')

    fun nextPageCyclic(cur: Int): Int = if (cur >= 899) 100 else cur + 1
    fun prevPageCyclic(cur: Int): Int = if (cur <= 100) 899 else cur - 1

    /**
     * Conoscenza sulle sottopagine.
     * Per ora: 102 ha 11 sottopagine. Le altre 1.
     */
    fun maxSubpagesFor(pageInt: Int): Int = when (pageInt) {
        102 -> 11
        else -> 1
    }

    fun nextSubCyclic(curSub: Int, maxSub: Int): Int = if (curSub >= maxSub) 1 else curSub + 1
    fun prevSubCyclic(curSub: Int, maxSub: Int): Int = if (curSub <= 1) maxSub else curSub - 1

    // ✅ Primo load: piccola delay per evitare glitch iniziale emulatore (la tua pagina 100 “sparisce”)
    LaunchedEffect(Unit) {
        delay(80)
        load("101", pushToHistory = false)
    }

    // ✅ Box per poter mettere l'overlay sopra la UI
    Box(modifier = Modifier.fillMaxSize()) {

        TelevideoScreen(
            currentPage = currentPage,
            bitmap = bitmap,
            clickAreas = clickAreas,
            isLoading = isLoading,
            errorText = errorText,
            bookmarks = bookmarks,
            showBookmarks = showBookmarks,
            onShowBookmarksChange = { value ->
                if (showBookmarks != value) showBookmarks = value
            },
            onLoadPage = { load(it, pushToHistory = true) },

            // ✅ SWIPE PAGINA: CICLICO 100..899 (e va in history)
            onSwipePage = { delta ->
                val pageInt = currentPage.toIntOrNull() ?: return@TelevideoScreen
                val cur = pageInt.coerceIn(100, 899)

                val next = when {
                    delta > 0 -> nextPageCyclic(cur)
                    delta < 0 -> prevPageCyclic(cur)
                    else -> cur
                }
                load(fmtPage(next), pushToHistory = true)
            },

            // ✅ SWIPE SUB: CICLICO 01..maxSubpages (e va in history)
            onSwipeSub = { delta ->
                val pageInt = currentPage.toIntOrNull() ?: return@TelevideoScreen
                val maxSub = maxSubpagesFor(pageInt).coerceAtLeast(1)

                if (maxSub == 1) return@TelevideoScreen

                val subInt = currentSubpage.toIntOrNull() ?: return@TelevideoScreen
                val curSub = subInt.coerceIn(1, maxSub)

                val nextSub = when {
                    delta > 0 -> nextSubCyclic(curSub, maxSub)
                    delta < 0 -> prevSubCyclic(curSub, maxSub)
                    else -> curSub
                }
                load("${fmtPage(pageInt)}-${fmtSub(nextSub)}", pushToHistory = true)
            },

            onAddBookmark = { pageStr ->
                val n = pageStr.toIntOrNull() ?: return@TelevideoScreen
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
            },

            // ✅ Undo: lo usi per il TAP a 2 dita (NON per bottone)
            onUndo = { undo() }
        )

        // ✅ Splash semitrasparente: ogni avvio, chiusura manuale con OK
        GestureHelpOverlay(
            visible = showHelp,
            onDismiss = { showHelp = false }
        )
    }
}