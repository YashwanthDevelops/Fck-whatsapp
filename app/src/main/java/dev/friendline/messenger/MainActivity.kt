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
import dev.friendline.messenger.push.PushNotificationRoutePolicy

class MainActivity : ComponentActivity() {
    private lateinit var messengerViewModel: MessengerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        messengerViewModel = ViewModelProvider(
            this,
            MessengerViewModelFactory(applicationContext),
        )[MessengerViewModel::class.java]
        handleNotificationRoute(intent)

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationRoute(intent)
    }

    private fun handleNotificationRoute(intent: android.content.Intent?) {
        messengerViewModel.openNotificationRoom(
            intent?.getStringExtra(PushNotificationRoutePolicy.EXTRA_ROOM_ID),
            intent?.getStringExtra(PushNotificationRoutePolicy.EXTRA_EVENT_ID),
        )
        intent?.removeExtra(PushNotificationRoutePolicy.EXTRA_ROOM_ID)
        intent?.removeExtra(PushNotificationRoutePolicy.EXTRA_EVENT_ID)
    }

    override fun onStop() {
        if (::messengerViewModel.isInitialized) {
            messengerViewModel.onAppStopped()
        }
        super.onStop()
    }
}
