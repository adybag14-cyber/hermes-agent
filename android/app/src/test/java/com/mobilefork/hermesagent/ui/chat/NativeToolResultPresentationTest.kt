package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeToolResultPresentationTest {
    @Test
    fun onlyTheStructuredNativeDenialIsTerminalAndEveryLanguageHasItsOwnMessage() {
        fun blocked() = JSONObject().put("exit_code", 126)
            .put("sandbox_execution_mode", "request_owned_proot_blocked")
            .put("request_owned_operation_blocked", true)
        assertNull(nativeSandboxPolicyDenial("Permission denied", "en"))
        assertNull(nativeSandboxPolicyDenial(blocked().put("exit_code", 0).toString(), "en"))
        assertNull(nativeSandboxPolicyDenial(blocked().put("sandbox_execution_mode", "proot").toString(), "en"))
        assertNull(nativeSandboxPolicyDenial(blocked().put("request_owned_operation_blocked", false).toString(), "en"))
        assertNull(nativeSandboxPolicyDenial(blocked().put("request_owned_operation_blocked", "true").toString(), "en"))
        AppLanguage.entries.forEach { language ->
            val message = nativeSandboxPolicyDenial(blocked().toString(), language.tag)
            assertEquals(sandboxStopPolicyMessage(language), message)
            if (language != AppLanguage.ENGLISH) {
                assertNotEquals(sandboxStopPolicyMessage(AppLanguage.ENGLISH), message)
            }
        }
    }
}
