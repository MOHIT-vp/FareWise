package com.farewise.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.farewise.app.ui.navigation.FareWiseNavHost
import com.farewise.app.ui.theme.FareWiseTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All screens live inside [FareWiseNavHost].
 *
 * [AndroidEntryPoint] is required so Hilt can inject [androidx.lifecycle.ViewModel]s
 * into Compose destinations via [androidx.hilt.navigation.compose.hiltViewModel].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the AndroidX splash screen *before* super.onCreate so the
        // brand icon shows on every API level the app supports. The library
        // reads Theme.FareWise.SplashScreen from the manifest and switches to
        // postSplashScreenTheme (Theme.FareWise) once the first frame is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FareWiseTheme {
                FareWiseNavHost()
            }
        }
    }
}
