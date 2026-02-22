@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.graphics.Bitmap
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

@Composable
private fun PageKeypadDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // ✅ ora parte VUOTO
    var digits by remember { mutableStateOf("") }

    fun tryAutoConfirmIfComplete() {
        if (digits.length != 3) return
        val n = digits.toIntOrNull() ?: return
        if (n in 100..899) {
            onConfirm(digits)
        }
        // se non valido, resta aperto e l'utente può correggere (C o backspace)
    }

    fun appendDigit(d: Char) {
        if (digits.length < 3) {
            digits += d
            // ✅ auto-conferma alla terza cifra
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
        // ✅ OK non necessario: lo tolgo proprio
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        }
    )
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
    onSwipeSub: (Int) -> Unit
) {
    var showKeypad by remember { mutableStateOf(false) }

    // --- Swipe ciclico (100..899) a scatto ---
    fun parsePageOrDefault(): Int = currentPage.toIntOrNull() ?: 100

    fun normalizeToRange(p: Int): Int = when {
        p < 100 -> 100
        p > 899 -> 899
        else -> p
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
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StarButton(
                        onTap = { onShowBookmarksChange(true) },
                        onLongPress = { onAddBookmark(currentPage) }
                    )

                    ToolbarButton("100") { onLoadPage("100") }
                    ToolbarButton("101") { onLoadPage("101") }
                    ToolbarButton("103") { onLoadPage("103") }

                    ToolbarButton("201") { onLoadPage("201") }
                    ToolbarButton("260") { onLoadPage("260") }

                    ToolbarButton("###") { showKeypad = true }
                }
            },
            actions = { },
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
                    onSwipePage = { delta -> handleSwipePage(delta) },
                    onSwipeSub = { delta -> handleSwipeSub(delta) },
                    debug = false
                )
            }
        }
    }
}