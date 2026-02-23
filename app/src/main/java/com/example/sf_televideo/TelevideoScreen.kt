@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun PageKeypadDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var digits by remember { mutableStateOf("") }

    fun tryAutoConfirmIfComplete() {
        if (digits.length != 3) return
        val n = digits.toIntOrNull() ?: return
        if (n in 100..899) {
            onConfirm(digits)
        }
    }

    fun appendDigit(d: Char) {
        if (digits.length < 3) {
            digits += d
            tryAutoConfirmIfComplete()
        }
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits = digits.dropLast(1)
    }

    fun clear() {
        digits = ""
    }

    val isComplete = digits.length == 3
    val valueInt = digits.toIntOrNull()
    val isValid = isComplete && valueInt != null && valueInt in 100..899

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vai a pagina") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val display = digits.padEnd(3, '•')
                Text(
                    text = display,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 28.sp
                )

                Spacer(Modifier.height(12.dp))

                @Composable
                fun KeyBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
                    FilledTonalButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.size(width = 74.dp, height = 52.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyBtn("1") { appendDigit('1') }
                        KeyBtn("2") { appendDigit('2') }
                        KeyBtn("3") { appendDigit('3') }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyBtn("4") { appendDigit('4') }
                        KeyBtn("5") { appendDigit('5') }
                        KeyBtn("6") { appendDigit('6') }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyBtn("7") { appendDigit('7') }
                        KeyBtn("8") { appendDigit('8') }
                        KeyBtn("9") { appendDigit('9') }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyBtn("C") { clear() }
                        KeyBtn("0") { appendDigit('0') }
                        KeyBtn("⌫") { backspace() }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val hint = when {
                    digits.isEmpty() -> "Inserisci 3 cifre (100–899)"
                    digits.length < 3 -> "Mancano ${3 - digits.length} cifre"
                    isValid -> "Apro ${digits}…"
                    else -> "Pagina non valida (100–899)"
                }

                Text(
                    text = hint,
                    color = if (isValid) Color(0xFF66BB6A) else Color.Gray,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    )
}

@Composable
private fun BookmarkSavedTopOverlay(
    visible: Boolean,
    text: String
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large,
                color = Color(0xCC000000),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
fun TelevideoScreen(
    currentPage: String,
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
    onSwipeSub: (Int) -> Unit,
    onUndo: () -> Unit                 // ✅ resta, ma NON c’è più il bottone
) {
    var showKeypad by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var showSaved by remember { mutableStateOf(false) }
    var savedText by remember { mutableStateOf("") }

    fun pageForToast(p: String): String {
        val n = p.toIntOrNull()
        return if (n != null) String.format("%03d", n) else p
    }

    fun showSavedToast(page: String) {
        savedText = "Pagina ${pageForToast(page)} salvata"
        showSaved = true
        scope.launch {
            delay(1200)
            showSaved = false
        }
    }

    fun formatPage(p: Int): String = String.format("%03d", p)

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
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StarButton(
                        onTap = { onShowBookmarksChange(true) },
                        onLongPress = { }
                    )

                    ToolbarButton("100") { onLoadPage("100") }
                    ToolbarButton("101") { onLoadPage("101") }
                    ToolbarButton("103") { onLoadPage("103") }

                    ToolbarButton("201") { onLoadPage("201") }
                    ToolbarButton("260") { onLoadPage("260") }
                }
            },
            actions = { /* niente undo button */ },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF101010),
                titleContentColor = Color.White,
                actionIconContentColor = Color.White
            )
        )

        if (showKeypad) {
            PageKeypadDialog(
                onDismiss = { showKeypad = false },
                onConfirm = { page3 ->
                    showKeypad = false
                    onLoadPage(page3)
                }
            )
        }

        if (showBookmarks) {
            BookmarksDialog(
                bookmarks = bookmarks,
                onDismiss = { onShowBookmarksChange(false) },
                onSelect = { p ->
                    onShowBookmarksChange(false)
                    onLoadPage(formatPage(p))
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

                    onLongPressPage = {
                        onAddBookmark(currentPage)
                        showSavedToast(currentPage)
                    },

                    onDoubleTapPage = {
                        showKeypad = true
                    },

                    // ✅ ECCO L’UNDO: tap a due dita
                    onTwoFingerTapPage = {
                        onUndo()
                    },

                    debug = false
                )
            }

            BookmarkSavedTopOverlay(
                visible = showSaved,
                text = savedText
            )
        }
    }
}