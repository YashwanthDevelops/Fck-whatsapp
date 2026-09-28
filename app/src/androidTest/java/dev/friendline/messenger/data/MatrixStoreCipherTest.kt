package dev.friendline.messenger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.TimelineItemContent
import uniffi.matrix_sdk_crypto.CollectStrategy
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class MatrixStoreCipherTest {
    @Test
    fun readReceiptPreferenceIsSavedInsideEncryptedDeviceVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = DeviceVault(context)
        val settingsFile = File(context.noBackupFilesDir, "private-messenger/settings.bin")

        vault.saveReadReceiptsEnabled(true)
        assertTrue(vault.loadReadReceiptsEnabled())
        assertFalse(
            "The stored privacy setting must not expose its field name in plaintext",
            String(settingsFile.readBytes(), Charsets.ISO_8859_1).contains("readReceiptsEnabled"),
        )

        vault.saveReadReceiptsEnabled(false)
        assertFalse(vault.loadReadReceiptsEnabled())
    }

    @Test
    fun wrongStoreKeyCannotReadPreviouslyStoredMessage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = DeviceVault(context)
        val session = checkNotNull(vault.loadSession()) { "Log in to the Android proof account first" }
        val deviceStoreKey = vault.loadOrCreateStoreKey()
        val sourceRoot = File(context.noBackupFilesDir, "private-messenger/matrix")
        val snapshotRoot = File(context.cacheDir, "matrix-store-cipher-snapshot")
        snapshotRoot.deleteRecursively()
        check(sourceRoot.copyRecursively(snapshotRoot, overwrite = true))

        val readableWith = openClient(snapshotRoot, deviceStoreKey)
        try {
            readableWith.restoreSession(session)
            assertTrue(
                "The correctly keyed store should recover the marker before the wrong-key probe",
                containsMarker(readableWith),
            )
        } finally {
            readableWith.close()
        }

        val wrongKey = ByteArray(32).also(SecureRandom()::nextBytes)
        var unreadableWith: Client? = null
        val wrongKeyWasRejected = try {
            val wrongKeyClient = openClient(snapshotRoot, wrongKey)
            unreadableWith = wrongKeyClient
            wrongKeyClient.restoreSession(session)
            !containsMarker(wrongKeyClient)
        } catch (_: Exception) {
            true
        } finally {
            unreadableWith?.close()
            snapshotRoot.deleteRecursively()
        }

        assertTrue("A different store key must not recover local plaintext", wrongKeyWasRejected)
    }

    private suspend fun openClient(root: File, key: ByteArray): Client {
        val cacheDirectory = File(root, "cache/matrix-cache.db")
        val store = SqliteStoreBuilder(File(root, "matrix.db").absolutePath, cacheDirectory.absolutePath)
            .key(key)
        return ClientBuilder()
            .homeserverUrl("http://127.0.0.1:8008")
            .disableWellKnownLookup(true)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .roomKeyRecipientStrategy(CollectStrategy.ONLY_TRUSTED_DEVICES)
            .sqliteStore(store)
            .build()
    }

    private suspend fun containsMarker(client: Client): Boolean = client.rooms().any { room ->
        val latest = room.latestEvent()
        val content = when (latest) {
            is LatestEventValue.Remote -> latest.content
            is LatestEventValue.Local -> latest.content
            else -> return@any false
        }
        val body = when (content) {
            is TimelineItemContent.MsgLike -> when (val kind = content.content.kind) {
                is MsgLikeKind.Message -> kind.content.body
                else -> null
            }
            else -> null
        }
        body?.contains(MARKER) == true
    }

    private companion object {
        const val MARKER = "POC_ENCRYPTED_MARKER_A9F"
    }
}
