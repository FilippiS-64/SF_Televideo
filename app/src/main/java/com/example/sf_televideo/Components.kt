package com.example.sf_televideo

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ToolbarButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            color = Color.White
        )
    }
}

@Composable
fun StarButton(onTap: () -> Unit, onLongPress: () -> Unit) {
    Text(
        text = "★",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    )
}

@Composable
fun BookmarksDialog(
    bookmarks: List<Int>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks.\nLong-press ★ to add the current page.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(bookmarks) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(p) {
                                    detectTapGestures(
                                        onTap = { onSelect(p) },
                                        onLongPress = { onRemove(p) }
                                    )
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = p.toString(),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "hold=remove",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun TelevideoImage(
    bitmap: Bitmap,
    clickAreas: List<ClickArea>,
    stretchY: Float,
    onTapArea: (ClickArea) -> Unit
) {
    if (bitmap.width <= 0 || bitmap.height <= 0) return

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = maxWidth
        val aspectHOverW = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeightDp = widthDp * aspectHOverW * stretchY

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Televideo",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .height(targetHeightDp)
                .pointerInput(bitmap, clickAreas) {
                    detectTapGestures { tap: Offset ->
                        if (size.width <= 0f || size.height <= 0f) return@detectTapGestures

                        val px = ((tap.x / size.width) * bitmap.width).roundToInt()
                        val py = ((tap.y / size.height) * bitmap.height).roundToInt()

                        val hit = clickAreas.firstOrNull { it.contains(px, py) }
                        if (hit != null) onTapArea(hit)
                    }
                }
        )
    }
}
