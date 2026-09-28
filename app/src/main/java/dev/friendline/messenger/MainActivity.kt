package dev.friendline.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.friendline.messenger.ui.MessengerApp
import dev.friendline.messenger.ui.MessengerViewModel
import dev.friendline.messenger.ui.MessengerViewModelFactory
import dev.friendline.messenger.ui.theme.MessengerTheme

class MainActivity : ComponentActivity() {
    private lateinit var messengerViewModel: MessengerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        messengerViewModel = ViewModelProvider(
            this,
            MessengerViewModelFactory(applicationContext),
        )[MessengerViewModel::class.java]

        setContent {
            MessengerTheme {
                MessengerApp(messengerViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::messengerViewModel.isInitialized) {
            messengerViewModel.onReturnedToAppFromExternalViewer()
        }
    }

    override fun onStop() {
        if (::messengerViewModel.isInitialized) {
            messengerViewModel.onAppStopped()
        }
        super.onStop()
    }
}
