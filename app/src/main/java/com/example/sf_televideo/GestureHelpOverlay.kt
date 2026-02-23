package com.example.sf_televideo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GestureHelpOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    // Overlay che blocca i tocchi dietro (clickable "vuoto" + no ripple)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.60f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* non chiudere al tap: chiusura SOLO con OK */ },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Gesture rapide",
                    style = MaterialTheme.typography.titleLarge,
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bullet("Tap su un numero", "apre la pagina")
                    Bullet("Swipe sinistra/destra", "pagina precedente/successiva")
                    Bullet("Swipe su/giù", "sottopagina precedente/successiva (se esiste)")
                    Bullet("Long press", "aggiunge bookmark (pagina corrente)")
                    Bullet("Tap a due dita", "UNDO (torna alla pagina precedente)")
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("OK")
                }
            }
        }
    }
}

@Composable
private fun Bullet(title: String, desc: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", fontSize = 18.sp)
        Column {
            Text(
                text = title,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Text(
                text = desc,
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
    }
}