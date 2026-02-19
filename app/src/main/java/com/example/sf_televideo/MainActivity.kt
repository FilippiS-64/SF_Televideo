@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sf_televideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ elimina la barra/titolo di sistema se presente
        // WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            androidx.compose.material3.MaterialTheme {
                TelevideoApp()
            }
        }
    }
}
