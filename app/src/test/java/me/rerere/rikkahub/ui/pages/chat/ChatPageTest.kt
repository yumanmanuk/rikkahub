package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPageTest {
    @Test
    fun `content hidden while conversation not loaded`() {
        assertFalse(shouldShowChatContent(conversationLoaded = false, chatListReady = false))
        assertFalse(shouldShowChatContent(conversationLoaded = false, chatListReady = true))
    }

    @Test
    fun `content hidden until initial scroll recorded`() {
        assertFalse(shouldShowChatContent(conversationLoaded = true, chatListReady = false))
    }

    @Test
    fun `content shown when loaded and scroll ready`() {
        assertTrue(shouldShowChatContent(conversationLoaded = true, chatListReady = true))
    }

    @Test
    fun `forcedOpen shows content regardless of load state`() {
        assertTrue(shouldShowChatContent(conversationLoaded = false, chatListReady = false, forcedOpen = true))
        assertTrue(shouldShowChatContent(conversationLoaded = true, chatListReady = false, forcedOpen = true))
    }

    @Test
    fun `default forcedOpen is false`() {
        assertFalse(shouldShowChatContent(conversationLoaded = false, chatListReady = false))
    }
}