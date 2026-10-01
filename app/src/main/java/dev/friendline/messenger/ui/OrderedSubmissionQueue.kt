package dev.friendline.messenger.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs accepted composer submissions in the exact order their UI callbacks enqueue them. */
internal class OrderedSubmissionQueue<T>(
    private val scope: CoroutineScope,
    private val consume: suspend (T) -> Unit,
) {
    private var tail: Job? = null

    fun enqueue(value: T) {
        val previous = tail
        tail = scope.launch {
            previous?.join()
            consume(value)
        }
    }

    suspend fun awaitIdle() {
        tail?.join()
    }
}

/** Per-room draft generations prevent an older async send from owning a newer draft. */
internal class ComposerDraftRevisionLedger {
    private val revisions = mutableMapOf<String, Long>()

    fun advance(roomId: String): Long {
        val revision = (revisions[roomId] ?: 0L) + 1L
        revisions[roomId] = revision
        return revision
    }

    fun current(roomId: String): Long = revisions[roomId] ?: 0L

    fun isCurrent(roomId: String, revision: Long): Boolean = current(roomId) == revision
}
