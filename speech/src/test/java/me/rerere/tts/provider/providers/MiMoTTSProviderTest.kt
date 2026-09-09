package me.rerere.tts.provider.providers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Test
    fun decode_audio_data_from_response() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_response_without_audio_data() {
        val data = """{"choices":[{"message":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }
}
