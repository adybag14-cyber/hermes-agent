package com.mobilefork.hermesagent.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HermesStringsTest {
    @Test
    fun chineseLocalizesMcpActionStatusText() {
        val strings = hermesStringsFor(AppLanguage.CHINESE)

        assertEquals(
            "已自动填充 MCP 配置，包含 1 个已启用的服务器定义。请检查后使用自动设置保存并重载。",
            strings.mcpStatusText(
                "Auto-filled MCP config with 1 enabled server definition. Review it, then use Auto setup to save and reload.",
            ),
        )
        assertEquals(
            "自动设置已准备 MCP 配置，包含 2 个已启用的服务器定义。",
            strings.mcpStatusText("Auto setup prepared MCP config with 2 enabled server definitions."),
        )
        assertEquals(
            "MCP 服务器名称为空。请先输入命令或服务器名称再添加。",
            strings.mcpStatusText("MCP server name is empty. Enter a command or server name before adding."),
        )
        assertEquals(
            "MCP 配置为空。请先添加 JSON 对象再重载。",
            strings.mcpStatusText("MCP config is empty. Add a JSON object before reloading."),
        )
        assertEquals(
            "已全局启用提供商缓存重发，但当前提供商不允许重发缓存上下文。",
            strings.mcpStatusText(
                "Provider cache resend is enabled globally, but openai disallows cached context resend.",
            ),
        )
    }

    @Test
    fun mcpActionStatusTextLocalizesForEveryNonEnglishLanguage() {
        val statuses = listOf(
            "Auto-filled MCP config with 1 enabled server definition. Review it, then use Auto setup to save and reload.",
            "Auto setup prepared MCP config with 2 enabled server definitions.",
            "MCP server name is empty. Enter a command or server name before adding.",
            "MCP config is empty. Add a JSON object before reloading.",
            "Provider cache resend is enabled globally, but openai disallows cached context resend.",
        )

        AppLanguage.values()
            .filterNot { it == AppLanguage.ENGLISH }
            .forEach { language ->
                val strings = hermesStringsFor(language)
                statuses.forEach { status ->
                    val localized = strings.mcpStatusText(status)
                    assertFalse("$language should not show raw status: $status", localized == status)
                    assertFalse("$language should translate the auto-fill review instruction", localized.contains("Review it"))
                }
            }
    }

    @Test
    fun chineseLocalizesMcpSimplePreviewGeneratedDescriptions() {
        val strings = hermesStringsFor(AppLanguage.CHINESE)
        val preview = """
            {
              "description": "Hermes Android local tools exposed to the agent runtime",
              "draft": "User-added MCP server draft",
              "hint": "Use Test \/ refresh after the command is installed on this device."
            }
        """.trimIndent()

        val localized = strings.mcpConfigPreviewText(preview)

        assertFalse(localized.contains("Hermes Android local tools exposed to the agent runtime"))
        assertFalse(localized.contains("User-added MCP server draft"))
        assertFalse(localized.contains("Use Test / refresh"))
        assertFalse(localized.contains("Use Test \\/ refresh"))
        assertEquals(
            true,
            localized.contains("Hermes Android 本地工具已暴露给代理运行时"),
        )
    }

    @Test
    fun localModelUiTextLocalizesCatalogAndDiskStatusForEveryNonEnglishLanguage() {
        val messages = listOf(
            "Tap Refresh catalog to load signed model choices when needed.",
            "Existing model file is present on disk",
            "Download file is present on disk",
            "Imported model file is missing on disk",
            "Android no longer reports this download",
            "Imported existing model file from disk",
        )

        AppLanguage.values()
            .filterNot { it == AppLanguage.ENGLISH }
            .forEach { language ->
                val strings = hermesStringsFor(language)
                messages.forEach { message ->
                    val localized = strings.localModelUiText(message)
                    assertFalse("$language should not show raw model status: $message", localized == message)
                }
            }
    }
}
