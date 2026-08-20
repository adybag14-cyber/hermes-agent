package com.mobilefork.hermesagent

import com.mobilefork.hermesagent.auth.CodexOAuthClient
import com.mobilefork.hermesagent.auth.XaiOAuthClient
import com.mobilefork.hermesagent.device.HermesTaskerImportBridge
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class MinSdk24CompatibilityTest {
    @Test
    fun oauthPkceEncodingUsesApi24SafeUnpaddedBase64Url() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, CodexOAuthClient.codeChallenge(verifier))
        assertEquals(expectedChallenge, XaiOAuthClient.codeChallenge(verifier))

        val codexStart = CodexOAuthClient.createBrowserStartRequest(methodId = "codex")
        val xaiStart = XaiOAuthClient.createStartRequest(
            discovery = XaiOAuthClient.Discovery(
                authorizationEndpoint = "https://auth.x.ai/authorize",
                tokenEndpoint = "https://auth.x.ai/oauth/token",
            ),
        )
        listOf(codexStart.pending.codeVerifier, xaiStart.pending.codeVerifier).forEach { generated ->
            assertEquals(43, generated.length)
            assertFalse(generated.contains('='))
            assertTrue(generated.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        }
    }

    @Test
    fun taskerBase64ImportsDecodeOnApi24() {
        val encoded =
            "PFRhc2tlckRhdGE+PFZhcmlhYmxlPjxubWU+JURFVklDRTwvbm1lPjx2YWw+cGhvbmU8L3ZhbD48L1ZhcmlhYmxlPjwvVGFza2VyRGF0YT4="
        val direct = requireNotNull(
            HermesTaskerImportBridge.bundleFromArguments(
                JSONObject().put("tasker_xml_base64", encoded),
            ),
        )
        val dataUri = requireNotNull(
            HermesTaskerImportBridge.bundleFromArguments(
                JSONObject().put("tasker_data_uri", "data:text/xml;base64,$encoded"),
            ),
        )

        assertEquals("phone", direct.bundle.getJSONObject("variables").getString("DEVICE"))
        assertEquals("phone", dataUri.bundle.getJSONObject("variables").getString("DEVICE"))
    }

    @Test
    fun directFileWriteParserHandlesEveryPathFormOnApi24() {
        val client = NativeToolCallingChatClient(RuntimeEnvironment.getApplication())

        assertEquals(
            "docs/double.txt" to "double quoted content",
            client.extractExplicitFileWriteRequestForTest(
                "Use file_write_tool to write \"docs/double.txt\" with content \"double quoted content\"",
            ),
        )
        assertEquals(
            "docs/single.txt" to "single quoted content",
            client.extractExplicitFileWriteRequestForTest(
                "Use write_file to create 'docs/single.txt' with content 'single quoted content'",
            ),
        )
        assertEquals(
            "docs/bare.txt" to "bare path content",
            client.extractExplicitFileWriteRequestForTest(
                "Use file_write_tool to save docs/bare.txt with content bare path content",
            ),
        )
    }
}
