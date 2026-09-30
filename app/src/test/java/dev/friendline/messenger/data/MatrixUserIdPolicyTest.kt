package dev.friendline.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixUserIdPolicyTest {
    @Test
    fun normalizesCompleteMatrixUserIds() {
        assertEquals("@alice:example.org", MatrixUserIdPolicy.normalize("  @alice:example.org  "))
        assertEquals("@alice:example.org:8448", MatrixUserIdPolicy.normalize("@alice:example.org:8448"))
    }

    @Test
    fun rejectsIncompleteOrAmbiguousMatrixUserIds() {
        listOf("alice:example.org", "@alice", "@:example.org", "@alice:example.org extra", "@alice example.org")
            .forEach { assertNull(MatrixUserIdPolicy.normalize(it)) }
    }
}
