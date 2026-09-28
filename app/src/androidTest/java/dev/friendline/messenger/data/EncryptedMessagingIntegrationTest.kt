package dev.friendline.messenger.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedMessagingIntegrationTest {
    @Test
    fun androidRecipientDecryptsMessageAndAcknowledgesDelivery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sender = MatrixRepository(instrumentation.targetContext)
        val args = InstrumentationRegistry.getArguments()
        val recipientUserId = checkNotNull(args.getString("recipient_user_id"))
        val recipientPassword = checkNotNull(args.getString("recipient_password"))
        val marker = "FRIENDLINE_ANDROID_E2EE_${UUID.randomUUID()}"
        val recipientLocalpart = recipientUserId.removePrefix("@").substringBefore(":")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val recipientStorage = File(
            instrumentation.targetContext.noBackupFilesDir,
            "integration-recipient-$recipientLocalpart",
        ).apply { mkdirs() }
        val recipient = MatrixRepository(
            IsolatedStorageContext(instrumentation.targetContext, recipientStorage),
        )

        try {
            checkNotNull(sender.restoreSession()) { "The Android sender must already be signed in" }
            val restoredRecipient = recipient.restoreSession()
            if (restoredRecipient == null) {
                recipient.login("http://127.0.0.1:8008", recipientUserId, recipientPassword)
            } else {
                check(restoredRecipient == recipientUserId) { "The isolated recipient store belongs to another account" }
            }
            val roomId = sender.createEncryptedConversation(listOf(recipientUserId), null)
            sender.openConversation(roomId)
            sender.sendText(roomId, marker)

            val invitationArrived = withTimeoutOrNull(45_000) {
                while (recipient.conversations.value.none { it.roomId == roomId }) {
                    recipient.refreshConversations()
                    delay(300)
                }
                true
            }
            check(invitationArrived == true) {
                "The recipient did not sync the room invitation; sync=${recipient.connection.value}, " +
                    "rooms=${recipient.conversations.value.size}"
            }
            recipient.joinConversation(roomId)
            recipient.openConversation(roomId)

            if (!sender.isPeerVerified(roomId)) {
                sender.requestPeerVerification(roomId)
                val requestArrived = withTimeoutOrNull(30_000) {
                    while (recipient.verification.value?.status != DeviceVerificationStatus.INCOMING_REQUEST) delay(300)
                    true
                }
                check(requestArrived == true) { "The recipient did not receive the device verification request" }
                recipient.acceptVerificationRequest()

                val sasReady = withTimeoutOrNull(45_000) {
                    while (sender.verification.value?.sas == null || recipient.verification.value?.sas == null) delay(300)
                    true
                }
                check(sasReady == true) { "The two clients did not reach the safety-code comparison step" }
                check(sender.verification.value?.sas == recipient.verification.value?.sas) {
                    "The two clients displayed different safety codes"
                }
                sender.approveVerification()
                recipient.approveVerification()
                val verified = withTimeoutOrNull(30_000) {
                    while (
                        sender.verification.value?.status != DeviceVerificationStatus.VERIFIED ||
                        recipient.verification.value?.status != DeviceVerificationStatus.VERIFIED
                    ) delay(300)
                    true
                }
                check(verified == true) { "Both clients did not complete SAS verification" }
            }

            sender.sendText(roomId, marker)

            val decrypted = withTimeoutOrNull(45_000) {
                while (recipient.messages.value.none { it.body == marker }) delay(300)
                true
            }
            check(decrypted == true) {
                "The recipient joined but did not decrypt the encrypted message; " +
                    "sync=${recipient.connection.value}, visibleMessages=${recipient.messages.value.size}"
            }

            val acknowledged = withTimeoutOrNull(45_000) {
                while (sender.messages.value.none { it.eventId != null && it.deliveryState == "Delivered" }) {
                    delay(300)
                }
                true
            }
            check(acknowledged == true) {
                "The recipient decrypted the message but the sender did not receive a delivery acknowledgement"
            }
        } finally {
            runCatching { recipient.close() }
            runCatching { sender.close() }
        }
    }

    private class IsolatedStorageContext(base: Context, private val storageRoot: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = storageRoot
    }
}
