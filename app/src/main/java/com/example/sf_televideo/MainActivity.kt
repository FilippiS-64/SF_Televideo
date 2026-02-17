package com.example.sf_televideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.jsoup.Jsoup
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TelevideoApp()
            }
        }
    }
}

@Composable
fun TelevideoApp() {
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("Premi bottone per Televideo pagina 100") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                scope.launch {
                    content = "Caricando pagina 100..."
                    try {
                        val text = withContext(Dispatchers.IO) {
                            val client = OkHttpClient()
                            val url = "https://www.servizitelevideo.rai.it/televideo/pub/solotesto.jsp?pagina=100"
                            val request = Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            val html = response.body?.string()
                            Jsoup.parse(html ?: "").body()?.text() ?: ""
                        }

                        val pageNumbers = mutableListOf<String>()
                        val pattern = Pattern.compile("\\bp?\\s*(\\d{3})\\b")
                        val matcher = pattern.matcher(text)
                        while (matcher.find()) {
                            pageNumbers.add(matcher.group(1)!!)
                        }

                        content = """
📺 TELEVIDEO RAI - PAGINA 100

$text.substring(0, minOf(1500, text.length))...

🔢 NUMERI PAGINE (per link automatici):
${pageNumbers.distinct().take(25).joinToString("\n")}
                        """.trimIndent()
                    } catch (e: Exception) {
                        content = "❌ Errore: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔴 Carica Televideo Pagina 100")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = content,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 22.sp
            )
        }
    }
}
