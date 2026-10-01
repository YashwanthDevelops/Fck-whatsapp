package dev.friendline.messenger.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
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
    fun androidRecipientDecryptsMessagesAndEncryptedImagesFromPeer() = runBlocking {
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
            val repeatedRoomId = sender.createEncryptedConversation(listOf(recipientUserId), null)
            check(repeatedRoomId == roomId) {
                "Adding the same Matrix user created a second encrypted one-to-one room: first=$roomId second=$repeatedRoomId"
            }
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
            val recipientRoom = checkNotNull(recipient.conversations.value.firstOrNull { it.roomId == roomId })
            if (recipientRoom.membership == "INVITED") recipient.acceptConversationInvitation(roomId)
            else check(recipientRoom.membership == "JOINED") { "The recipient is not invited or joined to the room" }
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

            val imageBytes = createTinyPng()
            val imageFixture = File(
                instrumentation.targetContext.cacheDir,
                "private-messenger/media/peer-image-${UUID.randomUUID()}.png",
            ).apply {
                parentFile?.mkdirs()
                writeBytes(imageBytes)
            }
            try {
                val imageUri = FileProvider.getUriForFile(
                    instrumentation.targetContext,
                    "${instrumentation.targetContext.packageName}.files",
                    imageFixture,
                )
                sender.sendAttachment(roomId, imageUri.toString())

                val receivedImage = withTimeoutOrNull(45_000) {
                    while (recipient.messages.value.none { it.attachment?.fileName == imageFixture.name }) {
                        delay(250)
                    }
                    recipient.messages.value.first { it.attachment?.fileName == imageFixture.name }
                }
                val receivedImageMessage = checkNotNull(receivedImage) {
                    "The peer did not receive the encrypted image event; " +
                        "sync=${recipient.connection.value}, messages=${recipient.messages.value.size}"
                }
                check(receivedImageMessage.attachment?.kind == AttachmentKind.IMAGE) {
                    "The peer's incoming attachment was not recognized as an image"
                }

                val attachment = checkNotNull(receivedImageMessage.attachment)
                val unavailableSource = sourceWithUnavailableMxcUri(attachment.sourceJson)
                val failedOpen = try {
                    recipient.loadAttachmentForViewing(
                        receivedImageMessage.copy(attachment = attachment.copy(sourceJson = unavailableSource)),
                    )
                    null
                } catch (failure: Exception) {
                    failure
                }
                check(failedOpen is MatrixAttachmentOpenFailure && failedOpen.stage == "download-and-decrypt") {
                    "The test's temporary media failure did not reach download/decrypt: " +
                        "failure=${failedOpen?.javaClass?.simpleName}, stage=${(failedOpen as? MatrixAttachmentOpenFailure)?.stage}"
                }

                val openedImage = recipient.loadAttachmentForViewing(receivedImageMessage)
                try {
                    check(openedImage.isFile && openedImage.readBytes().contentEquals(imageBytes)) {
                        "The receiving client did not open byte-for-byte image contents"
                    }
                    val openableUri = FileProvider.getUriForFile(
                        instrumentation.targetContext,
                        "${instrumentation.targetContext.packageName}.files",
                        openedImage,
                    )
                    check(openableUri.scheme == "content") { "The decrypted image is not exposable to Android's viewer" }
                    val uriBytes = instrumentation.targetContext.contentResolver
                        .openInputStream(openableUri)?.use { it.readBytes() }
                    check(uriBytes?.contentEquals(imageBytes) == true) {
                        "The Android viewer URI did not return the decrypted image bytes"
                    }
                } finally {
                    recipient.cleanupExternalViewerFiles()
                }
                check(!openedImage.exists()) { "The temporary decrypted image was not removed after viewer cleanup" }
            } finally {
                imageFixture.delete()
            }
        } finally {
            runCatching { recipient.close() }
            runCatching { sender.close() }
        }
    }

    private fun createTinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(37, 105, 87))
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sourceWithUnavailableMxcUri(sourceJson: String): String {
        val originalUri = Regex("""mxc://[^"\\,}]+""").find(sourceJson)?.value
            ?: error("The peer image MediaSource JSON did not contain an MXC URI")
        val finalSlash = originalUri.lastIndexOf('/')
        check(finalSlash > "mxc://".length) { "The peer image MXC URI was malformed" }
        val unreachableUri = originalUri.substring(0, finalSlash + 1) + "missing-${UUID.randomUUID()}"
        return sourceJson.replaceFirst(originalUri, unreachableUri)
    }

    private class IsolatedStorageContext(base: Context, private val storageRoot: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = storageRoot
    }
}
