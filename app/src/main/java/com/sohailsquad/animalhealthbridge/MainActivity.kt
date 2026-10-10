package com.sohailsquad.animalhealthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sohailsquad.animalhealthbridge.navigation.AhbNavHost
import com.sohailsquad.animalhealthbridge.ui.theme.AnimalHealthBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AnimalHealthBridgeTheme { AhbNavHost() } }
    }
}
