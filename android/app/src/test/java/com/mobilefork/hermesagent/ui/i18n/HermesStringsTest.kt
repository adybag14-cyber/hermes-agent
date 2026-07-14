package com.mobilefork.hermesagent.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun messageActionAndDiagnosticsLabelsLocalizeForAllLanguages() {
        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            assertFalse(strings.copyMessageLabel().isBlank())
            assertFalse(strings.editMessageLabel().isBlank())
            assertFalse(strings.resendMessageLabel().isBlank())
            assertFalse(strings.diagnosticsLogsTitle().isBlank())
            assertFalse(strings.diagnosticsExportLogsLabel().isBlank())
            assertFalse(strings.agentEndpointTitle().isBlank())
            assertFalse(strings.mcpQuickAddNativeTools().isBlank())
            if (language != AppLanguage.ENGLISH) {
                assertFalse(strings.copyMessageLabel() == "Copy")
                assertFalse(strings.diagnosticsLogsTitle() == "Diagnostics logs")
            }
        }
    }

    @Test
    fun bootStatusTextMapsEnglishRuntimeStatuses() {
        val strings = hermesStringsFor(AppLanguage.CHINESE)
        assertEquals("正在打开 Hermes…", strings.bootStatusText("Opening Hermes…"))
        assertEquals("Hermes 外壳已就绪", strings.bootStatusText("Hermes shell ready"))
    }

    @Test
    fun chatDisplayModeAndComposerLabelsLocalizeForAllLanguages() {
        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            assertFalse(strings.expandedModeLabel().isBlank())
            assertFalse(strings.compactModeLabel().isBlank())
            assertFalse(strings.moreInputActions().isBlank())
            assertFalse(strings.signalIntelligence().isBlank())
            assertEquals(strings.expandedModeLabel(), strings.chatDisplayModeLabel("expanded"))
            assertEquals(strings.expandedModeLabel(), strings.chatDisplayModeLabel(" Expanded "))
            assertEquals(strings.compactModeLabel(), strings.chatDisplayModeLabel("compact"))
            if (language != AppLanguage.ENGLISH) {
                assertFalse(strings.moreInputActions() == "More input actions")
                assertFalse(strings.expandedModeLabel() == "Expanded")
            }
        }
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

    @Test
    fun cardsKanbanAndLanguageAccessibilityLocalizeForEveryNonEnglishLanguage() {
        AppLanguage.entries.filterNot { it == AppLanguage.ENGLISH }.forEach { language ->
            val strings = hermesStringsFor(language)
            val localized = listOf(
                strings.selectedLanguageDescription("Español") to "Selected language Español",
                strings.switchLanguageDescription("Deutsch") to "Switch language to Deutsch",
                strings.kanbanDescription() to "Human board control for the shared Hermes kanban DB. Workers still need the gateway dispatcher.",
                strings.kanbanNewTask() to "New task",
                strings.localMemoryTitle() to "Local memory (hy-memory)",
                strings.automationsTitle() to "Phone automations",
                strings.skillsDescription() to "Installed Hermes skills from hermes-home and bundled skill directories.",
                strings.streamableHttpMcpDescription() to "Edge Gallery-style remote MCP: HTTPS URL that speaks Streamable HTTP. Optional API token is sent as Authorization.",
            )
            localized.forEach { (actual, english) ->
                assertFalse("$language should localize $english", actual == english)
                assertFalse("$language localization should not be blank", actual.isBlank())
            }
            assertFalse(strings.kanbanRuntimeText("Waiting for Hermes Python runtime…") == "Waiting for Hermes Python runtime…")
            assertFalse(strings.automationsStatusText("2 automation(s) on device") == "2 automation(s) on device")
            assertFalse(strings.localMemoryStatusText("hy-memory local companion · 3 facts") == "hy-memory local companion · 3 facts")
            assertFalse(strings.skillsStatusText("Skills refreshed (4)") == "Skills refreshed (4)")
        }
    }

    @Test
    fun agentTimelineSettingsAndTerminalLabelsLocalizeForEveryLanguage() {
        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            val values = listOf(
                strings.settingsPageLabel("Models"),
                strings.settingsBreadcrumb("Tools"),
                strings.showStepsLabel(),
                strings.hideStepsLabel(),
                strings.stopLabel(),
                strings.eventTypeLabel("thought"),
                strings.eventTypeLabel("tool_call"),
                strings.eventTypeLabel("file_access"),
                strings.terminalTitle(),
                strings.terminalDescription(),
                strings.commandLabel(),
                strings.signalToolsToggleLabel(false),
                strings.signalToolsToggleLabel(true),
                strings.noCommandOutputLabel(),
                strings.commandFailedLabel(),
                strings.exitCodeLabel(0),
                strings.chatStatusText("Stopped by user"),
                strings.uiFontSizeLabel(0.9f),
            )
            assertTrue(values.all { it.isNotBlank() })
            if (language != AppLanguage.ENGLISH) {
                assertFalse(strings.showStepsLabel() == "Show steps")
                assertFalse(strings.eventTypeLabel("thought") == "Think")
                assertFalse(strings.terminalTitle() == "Manual Linux terminal")
                assertFalse(strings.signalToolsToggleLabel(false) == "Show signal tools")
                assertFalse(strings.exitCodeLabel(0) == "Exit code 0")
                assertFalse(strings.chatStatusText("Stopped by user") == "Stopped by user")
            }
        }
    }
}
