package app.fwchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.fwchat.ui.shell.AppRoot
import app.fwchat.ui.theme.FwChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as FwChatApp).container
        setContent {
            FwChatTheme {
                AppRoot(container)
            }
        }
    }
}
