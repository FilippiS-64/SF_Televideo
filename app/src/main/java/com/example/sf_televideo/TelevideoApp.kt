@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun TelevideoApp() {
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

    fun load(page: String) {
        val p = page.toIntOrNull() ?: return
        if (p !in 100..899) return

        scope.launch {
            isLoading = true
            errorText = null
            try {
                val bmpJob = async { repo.fetchBitmap(page) }
                val mapJob = async { repo.fetchClickAreas(page) }
                bitmap = bmpJob.await()
                clickAreas = mapJob.await()
                currentPage = page
                currentSubpage = "01"
            } catch (e: Exception) {
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
        onAddBookmark = { page ->
            val n = page.toIntOrNull() ?: return@TelevideoScreen
            if (!bookmarks.contains(n)) {
                bookmarks.add(n)
                bookmarks.sort()
            }
        },
        onRemoveBookmark = { bookmarks.remove(it) }
    )
}
