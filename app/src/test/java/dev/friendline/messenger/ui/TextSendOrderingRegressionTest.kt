package dev.friendline.messenger.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSendOrderingRegressionTest {
    @Test
    fun rapidSubmissionsAreProcessedInEnqueueOrder() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstMayFinish = CompletableDeferred<Unit>()
        val observed = mutableListOf<String>()
        val queue = OrderedSubmissionQueue<String>(scope) { body ->
            observed += body
            if (body == "first") firstMayFinish.await()
        }

        try {
            queue.enqueue("first")
            queue.enqueue("second")
            queue.enqueue("third")
            yield()
            assertEquals(listOf("first"), observed)

            firstMayFinish.complete(Unit)
            queue.awaitIdle()
            assertEquals(listOf("first", "second", "third"), observed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun olderSendCompletionCannotOwnANewerComposerDraft() {
        val revisions = ComposerDraftRevisionLedger()
        val sendRevision = revisions.advance("!room:example.org")

        val newerDraftRevision = revisions.advance("!room:example.org")
        val anotherRoomRevision = revisions.advance("!another:example.org")
        assertFalse(revisions.isCurrent("!room:example.org", sendRevision))
        assertTrue(revisions.isCurrent("!room:example.org", newerDraftRevision))
        assertEquals(1L, revisions.current("!another:example.org"))
        assertTrue(revisions.isCurrent("!another:example.org", anotherRoomRevision))
    }
}
