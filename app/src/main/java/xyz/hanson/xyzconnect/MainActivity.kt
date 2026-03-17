package xyz.hanson.fosslink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.hanson.fosslink.ui.navigation.FossLinkNavHost
import xyz.hanson.fosslink.ui.theme.FossLinkTheme
import xyz.hanson.fosslink.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FossLinkTheme {
                val viewModel: MainViewModel = viewModel()
                FossLinkNavHost(viewModel)
            }
        }
    }
}
