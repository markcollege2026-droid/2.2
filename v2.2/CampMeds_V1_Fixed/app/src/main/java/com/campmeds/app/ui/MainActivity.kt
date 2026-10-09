package com.campmeds.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.campmeds.app.CampMedsApp
import com.campmeds.app.ui.navigation.CampMedsNavHost
import com.campmeds.app.ui.theme.CampMedsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CampMedsApp

        setContent {
            CampMedsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CampMedsNavHost(app = app)
                }
            }
        }
    }
}
