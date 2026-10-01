package dev.friendline.messenger.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.friendline.messenger.BuildConfig
import dev.friendline.messenger.MainActivity
import dev.friendline.messenger.R
import dev.friendline.messenger.data.DeviceVault
import dev.friendline.messenger.data.HomeserverUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Session values passed by the authenticated Matrix repository. The access token is never logged. */
class MatrixPushSession(
    val homeserverUrl: String,
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val deviceDisplayName: String = "Android device",
)

enum class PushRegistrationStatus {
    NOT_ENABLED,
    DISABLED,
    REGISTERING,
    REMOVING,
    REMOVAL_PENDING,
    PERMISSION_REQUIRED,
    PROVIDER_UNAVAILABLE,
    REGISTERED,
    REMOVED,
    FAILED;

    val displayText: String
        get() = when (this) {
            NOT_ENABLED -> "Message notifications are off."
            DISABLED -> "Notifications are unavailable in this build."
            REGISTERING -> "Setting up private alerts…"
            REMOVING -> "Removing this device's push registration…"
            REMOVAL_PENDING -> "Push cleanup is pending. Retry it before signing out."
            PERMISSION_REQUIRED -> "Allow notifications in device settings to receive alerts."
            PROVIDER_UNAVAILABLE -> "The push service could not provide a device token."
            REGISTERED -> "Enabled. Alerts use generic text and never show message content."
            REMOVED -> "Message notifications are off."
            FAILED -> "Couldn't update notification registration. Try again."
        }
}

/**
 * Native push boundary for MatrixRepository/MessengerViewModel to integrate after sign-in,
 * session restore, FCM token refresh, and before sign-out.
 */
object MatrixPushClient {
    const val APP_ID = "dev.friendline.messenger.android"
    private const val APP_DISPLAY_NAME = "Private Messenger (Android)"
    private const val PUSHER_ENDPOINT = "/_matrix/client/v3/pushers/set"
    private const val GATEWAY_PATH = "/_matrix/push/v1/notify"

    private val _refreshedTokens = MutableStateFlow<String?>(null)

    val isConfigured: Boolean
        get() = configured()

    /** Observe FCM token rotations while the app process is running. Do not log or persist values. */
    val refreshedTokens = _refreshedTokens.asStateFlow()

    fun hasNotificationPermission(context: Context): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Use with Activity Result APIs; permission prompts should follow a user action. */
    fun notificationPermissionContract() = ActivityResultContracts.RequestPermission()

    fun notificationPermissionToRequest(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    fun clearDisplayedNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Gets the current FCM registration token and registers an event_id_only Matrix pusher. */
    suspend fun register(context: Context, session: MatrixPushSession): PushRegistrationStatus {
        if (!configured()) return PushRegistrationStatus.DISABLED
        if (!hasNotificationPermission(context)) return PushRegistrationStatus.PERMISSION_REQUIRED

        val token = currentToken() ?: return PushRegistrationStatus.PROVIDER_UNAVAILABLE
        return registerToken(context, session, token)
    }

    /** Fetch the current provider token so the repository can persist it before registration. */
    suspend fun currentToken(): String? = fcmToken()

    /** Clean sign-out hook. Call before invalidating the Matrix access token. */
    suspend fun unregister(
        context: Context,
        session: MatrixPushSession,
        pushToken: String? = null,
    ): PushRegistrationStatus {
        val token = pushToken?.takeIf(String::isNotBlank)
            ?: fcmToken()
            ?: return PushRegistrationStatus.PROVIDER_UNAVAILABLE
        return removeToken(session, token)
    }

    /** Register an already acquired token, useful to integrate the token-refresh flow. */
    suspend fun registerToken(
        context: Context,
        session: MatrixPushSession,
        pushToken: String,
    ): PushRegistrationStatus {
        if (!configured()) return PushRegistrationStatus.DISABLED
        if (!hasNotificationPermission(context)) return PushRegistrationStatus.PERMISSION_REQUIRED
        if (pushToken.isBlank()) return PushRegistrationStatus.PROVIDER_UNAVAILABLE
        return postPusher(session, pushToken, remove = false)
    }

    private suspend fun removeToken(
        session: MatrixPushSession,
        pushToken: String,
    ): PushRegistrationStatus = postPusher(session, pushToken, remove = true)

    private suspend fun fcmToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.awaitResult().takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun configured(): Boolean =
        BuildConfig.PUSH_ENABLED && pushGatewayUrl() != null

    private fun pushGatewayUrl(): String? {
        if (!BuildConfig.PUSH_ENABLED) return null
        val domain = BuildConfig.PUSH_MATRIX_DOMAIN.trim()
        if (domain.isEmpty()) return null
        val uri = runCatching { URI("https://$domain") }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || !uri.rawPath.isNullOrEmpty()
        ) return null
        return "https://${uri.rawAuthority}$GATEWAY_PATH"
    }

    private suspend fun postPusher(
        session: MatrixPushSession,
        pushToken: String,
        remove: Boolean,
    ): PushRegistrationStatus = withContext(Dispatchers.IO) {
        try {
            val endpoint = pusherEndpoint(session.homeserverUrl)
            val gateway = if (remove) null else pushGatewayUrl()
                ?: return@withContext PushRegistrationStatus.DISABLED

            val payload = JSONObject()
                .put("app_id", APP_ID)
                .put("pushkey", pushToken)
            if (remove) {
                payload.put("kind", JSONObject.NULL)
            } else {
                payload.put("kind", "http")
                    .put("app_display_name", APP_DISPLAY_NAME)
                    .put("device_display_name", session.deviceDisplayName.ifBlank { "Android device" })
                    .put("lang", Locale.getDefault().language.ifBlank { "en" })
                    .put("data", JSONObject()
                        .put("format", "event_id_only")
                        .put("url", gateway))
            }

            val connection = (endpoint.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    PushRegistrationStatus.FAILED
                } else if (remove) {
                    PushRegistrationStatus.REMOVED
                } else {
                    PushRegistrationStatus.REGISTERED
                }
            } finally {
                connection.disconnect()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PushRegistrationStatus.FAILED
        }
    }

    private fun pusherEndpoint(homeserverUrl: String): URL {
        val normalized = HomeserverUrlPolicy.normalize(homeserverUrl, allowPrivateHttp = false)
        val uri = URI("$normalized$PUSHER_ENDPOINT")
        require(uri.scheme == "https" && uri.host != null) { "Push registration requires an HTTPS homeserver." }
        return uri.toURL()
    }

    private fun publishFcmToken(token: String) {
        if (token.isNotBlank()) _refreshedTokens.value = token
    }

    private suspend fun Task<String>.awaitResult(): String = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    internal fun onFcmTokenRefreshed(token: String) = publishFcmToken(token)
}

/** Receives FCM data-only wakeups and always posts fixed, content-free notification copy. */
class PrivateMessengerFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (!runCatching { DeviceVault(applicationContext).loadPushNotificationsEnabled() }.getOrDefault(false)) return
        // Only opaque Matrix room/event identifiers are consumed. Sender and content fields are ignored.
        GenericMessageNotification.show(this, PushNotificationRoutePolicy.parse(message.data))
    }

    override fun onNewToken(token: String) {
        // The active repository can observe this flow and re-register the rotated token.
        MatrixPushClient.onFcmTokenRefreshed(token)
    }
}

private object GenericMessageNotification {
    private const val CHANNEL_ID = "messages"
    private const val CHANNEL_NAME = "Messages"
    fun show(context: Context, route: PushNotificationRoute?) {
        if (!MatrixPushClient.hasNotificationPermission(context)) return
        val notificationId = PushNotificationRoutePolicy.stableNotificationId(route)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Generic alerts for new messages"
                },
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                route?.let {
                    putExtra(PushNotificationRoutePolicy.EXTRA_ROOM_ID, it.roomId)
                    it.eventId?.let { eventId -> putExtra(PushNotificationRoutePolicy.EXTRA_EVENT_ID, eventId) }
                }
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_push_notification)
            .setContentTitle("Private Messenger")
            .setContentText("New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
