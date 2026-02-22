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
                    onSwipePage = { delta -> onSwipePage(delta) },
                    onSwipeSub = { delta -> onSwipeSub(delta) },
                    debug = false
                )
            }
        }
    }
}