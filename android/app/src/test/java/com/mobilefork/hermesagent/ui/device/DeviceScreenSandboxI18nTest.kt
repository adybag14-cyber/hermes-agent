package com.mobilefork.hermesagent.ui.device

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeviceScreenSandboxI18nTest {
    @Test
    fun sandboxLabelsLocalizeForAllSupportedLanguages() {
        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            assertFalse(strings.deviceLinuxSandboxTitle().isBlank())
            assertFalse(strings.deviceLinuxSandboxSummary().isBlank())
            assertFalse(strings.deviceLinuxSandboxDeployLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxDeployAlpineLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxUpdateLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxStartLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxStopLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxMirrorLabel().isBlank())
            assertFalse(strings.deviceLinuxSandboxUninstallLabel().isBlank())
        }
    }

    @Test
    fun deployAlpineLabelUsesLocalizedCopyOutsideEnglishFallback() {
        assertEquals("部署 Alpine", hermesStringsFor(AppLanguage.CHINESE).deviceLinuxSandboxDeployAlpineLabel())
        assertEquals("Desplegar Alpine", hermesStringsFor(AppLanguage.SPANISH).deviceLinuxSandboxDeployAlpineLabel())
        assertEquals("Alpine bereitstellen", hermesStringsFor(AppLanguage.GERMAN).deviceLinuxSandboxDeployAlpineLabel())
        assertEquals("Implantar Alpine", hermesStringsFor(AppLanguage.PORTUGUESE).deviceLinuxSandboxDeployAlpineLabel())
        assertEquals("Déployer Alpine", hermesStringsFor(AppLanguage.FRENCH).deviceLinuxSandboxDeployAlpineLabel())
        assertEquals("Deploy Alpine", hermesStringsFor(AppLanguage.ENGLISH).deviceLinuxSandboxDeployAlpineLabel())
    }
}