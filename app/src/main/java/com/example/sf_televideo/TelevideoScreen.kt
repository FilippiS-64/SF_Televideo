@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TelevideoScreen(
    currentPage: String,
    currentSubpage: String,
    bitmap: Bitmap?,
    clickAreas: List<ClickArea>,
    isLoading: Boolean,
    errorText: String?,
    bookmarks: List<Int>,
    showBookmarks: Boolean,
    onShowBookmarksChange: (Boolean) -> Unit,
    onLoadPage: (String) -> Unit,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onSwipePage: (Int) -> Unit,
    onSwipeSub: (Int) -> Unit
) {
    // --- Swipe ciclico (100..899) a scatto ---
    fun parsePageOrDefault(): Int {
        // se currentPage non è numerico, ripiega su 100
        return currentPage.toIntOrNull() ?: 100
    }

    fun normalizeToRange(p: Int): Int {
        // se per qualunque motivo esci dal range, ti riporta dentro (fallback robusto)
        return when {
            p < 100 -> 100
            p > 899 -> 899
            else -> p
        }
    }

    fun nextPage(current: Int): Int {
        val c = normalizeToRange(current)
        return if (c >= 899) 100 else c + 1
    }

    fun previousPage(current: Int): Int {
        val c = normalizeToRange(current)
        return if (c <= 100) 899 else c - 1
    }

    fun formatPage(p: Int): String = String.format("%03d", p)

    fun handleSwipePage(delta: Int) {
        if (delta == 0) return
        val cur = parsePageOrDefault()
        val newPage = if (delta > 0) nextPage(cur) else previousPage(cur)
        onLoadPage(formatPage(newPage))
    }

    // Per ora lasciamo lo swipe subpage demandato al callback esterno (come prima).
    fun handleSwipeSub(delta: Int) {
        onSwipeSub(delta)
    }
    // --- fine swipe ciclico ---

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            navigationIcon = { },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StarButton(
                        onTap = { onShowBookmarksChange(true) },
                        onLongPress = { onAddBookmark(currentPage) }
                    )

                    ToolbarButton("100") { onLoadPage("100") }
                    ToolbarButton("101") { onLoadPage("101") }
                    ToolbarButton("103") { onLoadPage("103") }

                    // ✅ nuovi bottoni
                    ToolbarButton("201") { onLoadPage("201") }
                    ToolbarButton("260") { onLoadPage("260") }
                }
            },
            actions = { },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF101010),
                titleContentColor = Color.White,
                actionIconContentColor = Color.White
            )
        )

        if (showBookmarks) {
            BookmarksDialog(
                bookmarks = bookmarks,
                onDismiss = { onShowBookmarksChange(false) },
                onSelect = { p ->
                    onShowBookmarksChange(false)
                    onLoadPage(formatPage(p)) // ✅ garantisce 3 cifre anche dai bookmark
                },
                onRemove = { onRemoveBookmark(it) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap == null) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = errorText ?: "No image",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                TelevideoImage(
                    bitmap = bitmap,
                    clickAreas = clickAreas,
                    stretchY = 2.5f,
                    onTapArea = { onLoadPage(it.page) },
                    // ✅ swipe pagina: ora lo gestiamo qui, ciclico 100..899 e a scatto
                    onSwipePage = { delta -> handleSwipePage(delta) },
                    // ✅ swipe subpage: lasciamo come prima (se poi vuoi, lo rendiamo ciclico anche per le sub)
                    onSwipeSub = { delta -> handleSwipeSub(delta) },
                    debug = false
                )
            }
        }
    }
}