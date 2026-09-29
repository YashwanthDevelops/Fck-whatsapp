package dev.friendline.messenger.data

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedAttachmentIntegrationTest {
    @Test
    fun encryptedRoomImagesAndFilesCanBeDownloadedAndDecrypted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = MatrixRepository(context)
        val fixtureText = "FRIENDLINE_ATTACHMENT_ROUNDTRIP_${UUID.randomUUID()}"
        val fixture = File(context.cacheDir, "private-messenger/media/test-${UUID.randomUUID()}.txt")
        fixture.parentFile?.mkdirs()
        fixture.writeText(fixtureText)
        try {
            checkNotNull(repository.restoreSession()) { "Log in to the Android proof account first" }
            val ready = withTimeoutOrNull(30_000) {
                while (repository.connection.value != "Connected" || repository.conversations.value.isEmpty()) delay(250)
                true
            }
            check(ready == true) { "The Android proof account did not finish initial sync" }
            val roomId = repository.conversations.value.firstOrNull {
                it.isEncrypted && it.membership == "JOINED"
            }?.roomId ?: error("The proof account has no joined encrypted conversation")
            repository.openConversation(roomId)

            assertAttachmentRoundTrip(repository, roomId, fixture, AttachmentKind.FILE)

            val imageBytes = createTinyPng()
            val imageFixture = File(context.cacheDir, "private-messenger/media/image-${UUID.randomUUID()}.png")
                .apply { parentFile?.mkdirs(); writeBytes(imageBytes) }
            try {
                assertAttachmentRoundTrip(repository, roomId, imageFixture, AttachmentKind.IMAGE)
            } finally {
                imageFixture.delete()
            }
        } finally {
            fixture.delete()
            repository.close()
        }
        val leakedFixture = File(context.cacheDir, "private-messenger/media").walkTopDown()
            .filter(File::isFile)
            .any { file -> file.readText().contains(fixtureText) }
        assertEquals(false, leakedFixture)
    }

    private suspend fun assertAttachmentRoundTrip(
        repository: MatrixRepository,
        roomId: String,
        fixture: File,
        expectedKind: AttachmentKind,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", fixture)
        repository.sendAttachment(roomId, contentUri.toString())

        val attachmentMessage = withTimeoutOrNull(45_000) {
            while (repository.messages.value.none { it.attachment?.fileName == fixture.name }) delay(250)
            repository.messages.value.first { it.attachment?.fileName == fixture.name }
        }
        check(attachmentMessage != null) {
            "The SDK did not add the uploaded attachment to the encrypted room timeline " +
                "(connection=${repository.connection.value}, " +
                "preview=${repository.conversations.value.firstOrNull { it.roomId == roomId }?.preview}, " +
                "messages=${repository.messages.value.size}, " +
                "attachmentKinds=${repository.messages.value.mapNotNull { it.attachment?.kind }})"
        }
        assertEquals(expectedKind, attachmentMessage.attachment?.kind)
        val downloaded = repository.loadAttachmentForViewing(attachmentMessage)
        assertArrayEquals(fixture.readBytes(), downloaded.readBytes())
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
}
