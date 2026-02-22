@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onRemoveBookmark: (Int) -> Unit
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            navigationIcon = { },
            title = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StarButton(
                        onTap = { onShowBookmarksChange(true) },
                        onLongPress = { onAddBookmark(currentPage) }
                    )

                    // bottoni rapidi
                    ToolbarButton("100") { onLoadPage("100") }
                    ToolbarButton("101") { onLoadPage("101") }
                    ToolbarButton("103") { onLoadPage("103") }

                    // richiesti da te al posto delle frecce
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
                    onLoadPage(p.toString())
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

                    // swipe pagina
                    onSwipePage = { delta ->
                        if (isLoading) return@TelevideoImage
                        val pageInt = currentPage.toIntOrNull() ?: return@TelevideoImage
                        val next = pageInt + delta
                        if (next in 100..899) {
                            Log.d("TVDBG", "onSwipePage delta=$delta current=$currentPage -> next=$next")
                            onLoadPage(next.toString())
                        }
                    },

                    // swipe subpage
                    onSwipeSub = { delta ->
                        if (isLoading) return@TelevideoImage
                        val subInt = currentSubpage.toIntOrNull() ?: 1
                        val nextSub = subInt + delta
                        if (nextSub in 1..99) {
                            val sp = nextSub.toString().padStart(2, '0')
                            Log.d("TVDBG", "onSwipeSub delta=$delta currentSub=$currentSubpage -> nextSub=$sp")
                            onLoadPage("${currentPage}-$sp")
                        }
                    },

                    // niente overlay
                    debug = false
                )
            }
        }
    }
}