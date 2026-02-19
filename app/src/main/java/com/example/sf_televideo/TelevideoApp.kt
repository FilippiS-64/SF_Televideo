@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext

// ---------- DataStore ----------
private val Context.dataStore by preferencesDataStore(name = "sf_televideo_prefs")
private val BOOKMARKS_KEY = stringSetPreferencesKey("bookmarks_pages")

@Composable
fun TelevideoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TelevideoRepository() }

    var currentPage by remember { mutableStateOf("100") }
    var currentSubpage by remember { mutableStateOf("01") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var clickAreas by remember { mutableStateOf<List<ClickArea>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val bookmarks = remember { mutableStateListOf<Int>() }
    var showBookmarks by remember { mutableStateOf(false) }

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

    fun load(page: String) {
        val p = page.toIntOrNull() ?: return
        if (p !in 100..899) return

        scope.launch {
            isLoading = true
            errorText = null

            // ✅ IMPORTANTISSIMO: aggiorna SUBITO lo stato pagina richiesto
            // così ★ salva sempre la pagina che stai visualizzando/caricando
            currentPage = page
            currentSubpage = "01"

            // opzionale: reset aree mentre carichi
            clickAreas = emptyList()

            try {
                // carico bitmap e clickAreas in parallelo
                val bmpJob = async(Dispatchers.IO) { repo.fetchBitmap(page) }
                val areasJob = async { // lascia nel dispatcher interno del repo
                    repo.fetchClickAreas(page) // oppure repo.fetchClickAreas(page, bitmap) se usi OCR+bitmap
                }

                // 1) bitmap: se fallisce, non puoi mostrare la pagina → errore vero
                val bmp = bmpJob.await()
                bitmap = bmp

                // 2) clickAreas: se falliscono, NON bloccare la pagina
                clickAreas = try {
                    areasJob.await()
                } catch (_: Exception) {
                    emptyList()
                }
            } catch (e: Exception) {
                // se siamo qui è perché la bitmap non è arrivata (o altro grave)
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
