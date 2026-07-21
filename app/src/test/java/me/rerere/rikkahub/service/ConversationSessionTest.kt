package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    private fun createSession(): ConversationSession {
        val id = Uuid.random()
        return ConversationSession(
            id = id,
            initial = Conversation.ofId(id = id),
            scope = CoroutineScope(Dispatchers.Default + Job()),
            onIdle = {},
        )
    }

    @Test
    fun `session starts uninitialized`() {
        val session = createSession()

        assertFalse(session.initialized.value)
    }

    @Test
    fun `markInitialized marks session as initialized`() {
        val session = createSession()

        session.markInitialized()

        assertTrue(session.initialized.value)
    }

    @Test
    fun `initialized is not reset by release and reacquire`() {
        val session = createSession()
        session.markInitialized()

        session.acquire()
        session.release()
        session.acquire()

        assertTrue(session.initialized.value)
    }
}
