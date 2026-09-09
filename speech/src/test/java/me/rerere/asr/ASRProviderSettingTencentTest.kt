package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingTencentTest {
    @Test
    fun tencent_defaults_are_expected() {
        val setting = ASRProviderSetting.Tencent()

        assertEquals("Tencent ASR", setting.name)
        assertEquals("", setting.secretId)
        assertEquals("", setting.secretKey)
        assertEquals("", setting.appId)
        assertEquals("16k_zh", setting.engineModelType)
        assertEquals(1, setting.voiceFormat)
        assertEquals(16000, setting.sampleRate)
        assertEquals(0, setting.filterDirty)
        assertEquals(0, setting.filterModal)
        assertEquals(0, setting.filterPunc)
        assertEquals(1, setting.convertNumMode)
        assertEquals("", setting.hotwordId)
        assertEquals("", setting.customizationId)
    }

    @Test
    fun tencent_is_registered_in_provider_types() {
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.Tencent::class))
    }

    @Test
    fun tencent_copy_provider_preserves_extra_fields() {
        val original = ASRProviderSetting.Tencent(
            secretId = "AKID-test",
            secretKey = "secret",
            appId = "1258650085",
            engineModelType = "16k_en",
            voiceFormat = 1,
            sampleRate = 8000,
            filterDirty = 1,
            filterModal = 1,
            filterPunc = 1,
            convertNumMode = 0,
            hotwordId = "hw-1",
            customizationId = "custom-1",
        )
        val copied = original.copyProvider(id = original.id, name = "renamed")

        assertTrue(copied is ASRProviderSetting.Tencent)
        val tencent = copied as ASRProviderSetting.Tencent
        assertEquals("renamed", tencent.name)
        assertEquals("AKID-test", tencent.secretId)
        assertEquals("secret", tencent.secretKey)
        assertEquals("1258650085", tencent.appId)
        assertEquals("16k_en", tencent.engineModelType)
        assertEquals(1, tencent.voiceFormat)
        assertEquals(8000, tencent.sampleRate)
        assertEquals(1, tencent.filterDirty)
        assertEquals(1, tencent.filterModal)
        assertEquals(1, tencent.filterPunc)
        assertEquals(0, tencent.convertNumMode)
        assertEquals("hw-1", tencent.hotwordId)
        assertEquals("custom-1", tencent.customizationId)
    }
}
