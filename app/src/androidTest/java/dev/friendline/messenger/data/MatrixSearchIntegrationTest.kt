package dev.friendline.messenger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MatrixSearchIntegrationTest {
    @Test
    fun encryptedLocalSearchFindsKnownMessage() = runBlocking {
        val repository = MatrixRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            checkNotNull(repository.restoreSession()) { "Log in to the Android proof account first" }
            withTimeoutOrNull(30_000) {
                while (repository.connection.value != "Connected" || repository.conversations.value.isEmpty()) delay(250)
            }
            val roomId = repository.conversations.value.firstOrNull {
                it.isEncrypted && it.membership == "JOINED"
            }?.roomId ?: error("The proof account has no joined encrypted conversation")
            repository.openConversation(roomId)
            repository.sendText(roomId, MARKER)
            delay(500)
            repository.searchMessages(MARKER)
            val found = withTimeoutOrNull(15_000) {
                while (repository.searchResults.value.none { it.body.contains(MARKER) }) delay(250)
                true
            }
            check(found == true) {
                "The encrypted local search index did not return the known message " +
                    "(connection=${repository.connection.value}, loading=${repository.searchLoading.value}, " +
                    "hasMore=${repository.searchHasMore.value}, results=${repository.searchResults.value.size})"
            }
            val searchIndex = File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "private-messenger/matrix/search-index",
            )
            val leakedPlaintext = searchIndex.walkTopDown().filter { it.isFile }.any { file ->
                String(file.readBytes(), Charsets.ISO_8859_1).contains(MARKER)
            }
            check(!leakedPlaintext) { "The local search index contains the marker in plaintext" }
        } finally {
            repository.close()
        }
    }

    private companion object {
        const val MARKER = "pocsearchmarker"
    }
}
