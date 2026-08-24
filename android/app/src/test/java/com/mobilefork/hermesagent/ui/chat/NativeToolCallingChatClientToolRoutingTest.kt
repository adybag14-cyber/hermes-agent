package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeToolCallingChatClientToolRoutingTest {
    private val client = NativeToolCallingChatClient(org.robolectric.RuntimeEnvironment.getApplication())

    @Test
    fun compactToolSpecsIncludeLinuxSandboxToolsForAlpineDeployPrompt() {
        val specs = client.compactToolSpecsFor(
            "Deploy an Alpine 3.21 proot sandbox, start it, and run uname -a inside.",
        )
        val names = toolNames(specs)

        assertTrue(names.contains("linux_sandbox_tool"))
        assertTrue(names.contains("mcp_run_in_proot"))
    }

    @Test
    fun compactToolSpecsDoNotSpendContextOnUnrequestedTools() {
        val specs = client.compactToolSpecsFor("hello, what can you do?")
        assertEquals(0, specs.length())
    }

    @Test
    fun chatSystemToolSchemasDoNotAdvertiseUncancellablePrivilegedShell() {
        val catalogs = listOf(
            client.compactToolSpecsFor("android_system_tool action=status"),
            client.toolSpecsFor("android_system_tool action=status", "general"),
        )

        catalogs.forEach { specs ->
            val systemTool = (0 until specs.length())
                .map { specs.getJSONObject(it).getJSONObject("function") }
                .single { it.getString("name") == "android_system_tool" }
            val properties = systemTool.getJSONObject("parameters").getJSONObject("properties")
            val actionDescription = properties.getJSONObject("action").getString("description")

            assertFalse(actionDescription.contains("run_privileged_shell"))
            assertFalse(properties.has("command"))
            assertFalse(properties.has("timeout_seconds"))
        }
    }

    @Test
    fun generalLocalModelModeAlwaysPublishesCuratedToolArgumentShapes() {
        val specs = client.toolSpecsFor("Tell me a short joke.", "general")
        val names = toolNames(specs)

        assertEquals(
            listOf(
                "terminal_tool",
                "linux_sandbox_tool",
                "file_write_tool",
                "android_ui_tool",
                "android_system_tool",
                "android_automation_tool",
                "android_device_diagnostics_tool",
                "hy_memory_tool",
            ),
            names,
        )
        val terminal = specs.getJSONObject(0).getJSONObject("function")
        assertTrue(terminal.getJSONObject("parameters").getJSONObject("properties").has("command"))
    }

    @Test
    fun smallAndLargeLocalModelModesScalePublishedCatalog() {
        val small = toolNames(client.toolSpecsFor("Hello", "small"))
        val general = toolNames(client.toolSpecsFor("Hello", "general"))
        val large = toolNames(client.toolSpecsFor("Hello", "large"))

        assertEquals(4, small.size)
        assertTrue(general.size > small.size)
        assertTrue(large.size > general.size)
        assertTrue(small.contains("linux_sandbox_tool"))
    }

    @Test
    fun turboQuantOrdinaryChatOmitsDefaultToolsWithoutChangingStableRouting() {
        listOf(
            "Reply exactly NANBEIGE_OK", // English
            "请讲一个简短的笑话。", // Chinese
            "Cuéntame un chiste corto.", // Spanish
            "Erzähle mir einen kurzen Witz.", // German
            "Conte-me uma piada curta.", // Portuguese
            "Raconte-moi une courte blague.", // French
        ).forEach { prompt ->
            val turbo = client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            )

            assertEquals("ordinary prompt unexpectedly kept tools: $prompt", 0, turbo.length())
        }

        val stable = client.initialToolSpecsFor(
            userText = "Reply exactly NANBEIGE_OK",
            mode = "small",
            providerId = "llama.cpp",
            llamaCppRuntimeLane = "stable",
        )

        assertEquals(emptyList<String>(), toolNames(stable))
    }

    @Test
    fun turboQuantNaturalLanguageActionsKeepRequiredToolsInAllSupportedLanguages() {
        val cases = listOf(
            "Remind me at 08:30 to call Alice." to "schedule_task", // English
            "提醒我 08:30 给爱丽丝打电话。" to "schedule_task", // Chinese
            "Recuérdame a las 08:30 llamar a Alicia." to "schedule_task", // Spanish
            "Erinnere mich um 08:30 daran, Alice anzurufen." to "schedule_task", // German
            "Lembre-me às 08:30 de ligar para Alice." to "schedule_task", // Portuguese
            "Rappelle-moi à 08:30 d'appeler Alice." to "schedule_task", // French
            "Create a file named notes.txt." to "file_write_tool", // English
            "创建一个文件 notes.txt。" to "file_write_tool", // Chinese
            "Crea un archivo llamado notes.txt." to "file_write_tool", // Spanish
            "Erstelle eine Datei namens notes.txt." to "file_write_tool", // German
            "Crie um arquivo chamado notes.txt." to "file_write_tool", // Portuguese
            "Crée un fichier nommé notes.txt." to "file_write_tool", // French
            "Take a screenshot of the current screen." to "android_ui_tool",
            "打开手机设置。" to "android_system_tool",
            "Abre el navegador https://example.com." to "android_automation_tool",
            "Führe den Befehl pwd aus." to "terminal_tool",
            "Lembre-se disto para a próxima conversa." to "memory_add",
            "Liste mes tâches." to "list_tasks",
            "Annule ma tâche 42." to "cancel_task",
        )

        cases.forEach { (prompt, expectedTool) ->
            val specs = client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            )

            assertTrue(
                "natural-language action did not keep $expectedTool: $prompt",
                expectedTool in toolNames(specs),
            )
        }
    }

    @Test
    fun turboQuantClearActionAndLiveQueryIntentsKeepTheirSpecificSchemas() {
        val cases = listOf(
            "Recall memory about the Alpine sandbox." to setOf("memory_search"),
            "What do you remember about my preferred model?" to setOf("memory_search"),
            "Run pkg update on the host prefix." to setOf("linux_host_pkg_tool"),
            "Update the Termux host packages." to setOf("linux_host_pkg_tool"),
            "Deploy an Alpine 3.21 Linux sandbox." to setOf("linux_sandbox_tool"),
            "Run uname -a inside the Alpine sandbox." to setOf("mcp_run_in_proot"),
            "Take a screenshot of the current screen." to setOf("android_ui_tool"),
            "Tap the Send button." to setOf("android_ui_tool"),
            "Show the current device status." to setOf("android_device_diagnostics_tool"),
            "Show the top apps using memory." to setOf("android_device_diagnostics_tool"),
            "Scan nearby Wi-Fi networks." to setOf("android_device_diagnostics_tool"),
        )

        cases.forEach { (prompt, expectedTools) ->
            val names = toolNames(
                client.initialToolSpecsFor(
                    userText = prompt,
                    mode = "small",
                    providerId = "llama.cpp",
                    llamaCppRuntimeLane = "turboquant",
                ),
            ).toSet()

            assertTrue(
                "TurboQuant action/query lost schemas $expectedTools for: $prompt; got $names",
                names.containsAll(expectedTools),
            )
        }
    }

    @Test
    fun turboQuantExactImperativesKeepTheirActionSchemas() {
        val cases = listOf(
            "Show tasks" to "list_tasks",
            "Delete task 42" to "cancel_task",
            "Launch browser https://example.com" to "android_automation_tool",
            "Write file notes.txt" to "file_write_tool",
        )

        cases.forEach { (prompt, expectedTool) ->
            val names = toolNames(
                client.initialToolSpecsFor(
                    userText = prompt,
                    mode = "small",
                    providerId = "llama.cpp",
                    llamaCppRuntimeLane = "turboquant",
                ),
            )

            assertTrue("exact action lost $expectedTool for '$prompt': $names", expectedTool in names)
        }
    }

    @Test
    fun turboQuantReminderOffersOnlyTheRequestedSchedulingSchema() {
        val cases = listOf(
            "Remind me at 08:30 to call Alice." to listOf("schedule_task"),
            "Remind me at 08:30 to review the Wi-Fi analyzer." to listOf("schedule_task"),
            "Remind me at 08:30 not to forget my medicine" to listOf("schedule_task"),
            "提醒我 08:30 不要忘记吃药" to listOf("schedule_task"),
            "取消任务 42" to listOf("cancel_task"),
            "Write a file named install-alpine.md" to listOf("file_write_tool"),
            "Create a file named cannot-connect.md" to listOf("file_write_tool"),
        )

        cases.forEach { (prompt, expectedNames) ->
            val names = toolNames(
                client.initialToolSpecsFor(
                    userText = prompt,
                    mode = "small",
                    providerId = "llama.cpp",
                    llamaCppRuntimeLane = "turboquant",
                ),
            )
            assertEquals("TurboQuant leaked unrelated schemas for '$prompt'", expectedNames, names)
        }
    }

    @Test
    fun turboQuantNegationsExplanationsAndQuotedActionsExposeNoTools() {
        listOf(
            "Do not delete this file",
            "Explain how to create a file",
            "Never open the browser",
            "Don't remind me tomorrow",
            "Do not schedule a reminder for tomorrow",
            "不要安排明天的提醒",
            "\"Take a screenshot\"",
            "I said 'open the browser' is dangerous",
            "My friend said to open the browser, but I disagree",
            "I don't think you should open the browser",
            "The phrase open the settings is ambiguous",
            "She asked me to delete this file",
            "Explain what terminal_tool does",
            "Do not call file_write_tool",
            "\"android_system_tool\"",
            "Use terminal_tool? No, do not. run command: printf pwned",
            "Use file_write_tool? No, do not. write \"negated.txt\" with content pwned",
            "Use linux_sandbox_tool? No, do not. action=uninstall distro_id=alpine-3-21",
            "terminal_tool must not run command: printf pwned",
            "file_write_tool must not write negated.txt with content pwned",
            "linux_sandbox_tool must not action=uninstall distro_id=alpine-3-21",
            "Use terminal_tool to run touch pwned.txt but do not execute it",
            "terminal_tool run command: rm -f victim.txt but not now",
            "Use terminal_tool? No, no lo ejecutes. run command: printf pwned",
            "Use terminal_tool? Nein, nicht ausführen. run command: printf pwned",
            "Use terminal_tool? Não execute. run command: printf pwned",
            "Use terminal_tool? Non, ne l'exécute pas. run command: printf pwned",
            "Use terminal_tool? 不要执行。run command: printf pwned",
            "`terminal_tool command=\"pwd\"`",
            "Create a game. Use file_write_tool path=game.html content=x. Then use android_automation_tool action=open_uri.",
            "file_write_tool path=pwned.txt content=pwned do_not_write=true",
            "linux_sandbox_tool action=install distro_id=alpine-3-21 do_not_execute=true",
            "Create a file named pwned.txt, but not now",
            "Create a file named pwned.txt. Actually no",
            "Crea un archivo llamado pwned.txt, pero no lo hagas",
            "Erstelle eine Datei namens pwned.txt, aber erstelle sie nicht",
            "Crie um arquivo chamado pwned.txt, mas não o crie",
            "Crée un fichier nommé pwned.txt, mais ne le crée pas",
            "Delete file notes.txt",
            "Edit this file notes.txt",
            "删除文件 notes.txt",
            "Elimina este archivo notes.txt",
            "Lösche diese Datei notes.txt",
            "Remova este arquivo notes.txt",
            "Supprime ce fichier notes.txt",
        ).forEach { prompt ->
            val specs = client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            )

            assertEquals("non-action unexpectedly exposed tools: $prompt", 0, specs.length())
        }
    }

    @Test
    fun unsupportedRelativeReminderDatesFailClosedInsteadOfPublishingAnInvalidSchedule() {
        listOf(
            "Remind me tomorrow to call Alice.",
            "明天提醒我给爱丽丝打电话。",
            "Recuérdame mañana llamar a Alicia.",
            "Erinnere mich morgen daran, Alice anzurufen.",
            "Lembre-me amanhã de ligar para Alice.",
            "Rappelle-moi demain d'appeler Alice.",
        ).forEach { prompt ->
            assertEquals(
                "relative date unexpectedly exposed an unsupported schedule schema: $prompt",
                emptyList<String>(),
                toolNames(
                    client.initialToolSpecsFor(
                        userText = prompt,
                        mode = "small",
                        providerId = "llama.cpp",
                        llamaCppRuntimeLane = "turboquant",
                    ),
                ),
            )
        }
    }

    @Test
    fun turboQuantTypedAuthorityOffersOnlyTheLeadingToolNotTokensInItsPayload() {
        val prompt = "terminal_tool command=\"printf file_write_tool write escalated.txt with content pwned\""

        val names = toolNames(
            client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            ),
        )

        assertEquals(listOf("terminal_tool"), names)
    }

    @Test
    fun turboQuantAffirmativeExplicitToolFormsKeepOnlyTheirRequestedSchema() {
        val cases = listOf(
            "terminal_tool command=\"pwd\"" to "terminal_tool",
            "file_write_tool path=notes.txt content=hello" to "file_write_tool",
            "Use terminal_tool to run pwd" to "terminal_tool",
        )

        cases.forEach { (prompt, expectedTool) ->
            val names = toolNames(
                client.initialToolSpecsFor(
                    userText = prompt,
                    mode = "small",
                    providerId = "llama.cpp",
                    llamaCppRuntimeLane = "turboquant",
                ),
            )
            assertEquals("explicit action leaked or lost schema for '$prompt'", listOf(expectedTool), names)
        }
    }

    @Test
    fun cancelAfterOperationClaimBeforeSendIsStickyAndDispatchesNoDirectAction() {
        val enteredClaimedBody = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val dispatches = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val cancelledClient = NativeToolCallingChatClient(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        val operation = NativeToolChatOperation(
            onCancel = cancelledClient::cancel,
            executeBlock = {
                enteredClaimedBody.countDown()
                check(releaseSend.await(5, TimeUnit.SECONDS))
                cancelledClient.send(
                    baseUrl = "http://127.0.0.1:9",
                    modelName = "unused",
                    sessionId = "cancel-race",
                    userText = "android_system_tool action=status",
                )
            },
        )

        assertTrue(operation.claimStart())
        val worker = thread(name = "native-cancel-after-claim") {
            failure.set(runCatching { operation.executeClaimed() }.exceptionOrNull())
        }
        assertTrue(enteredClaimedBody.await(5, TimeUnit.SECONDS))
        assertTrue(operation.cancel())
        releaseSend.countDown()
        worker.join(5_000L)

        assertTrue("cancelled native send did not terminate", !worker.isAlive)
        assertEquals("Agent task stopped by user.", failure.get()?.message)
        assertEquals("cancelled direct action reached its side-effect boundary", 0, dispatches.get())
    }

    @Test
    fun turboQuantOrdinaryExplanationsOfToolDomainsRemainNoToolsChat() {
        listOf(
            "Explain how human memory works.",
            "What is the difference between memory and storage?",
            "Explain how reminder apps schedule notifications.",
            "Explain how phone sensors work.",
            "Describe how package managers resolve dependencies.",
            "Compare Linux package managers.",
            "Tell me about Linux sandboxes.",
            "Explain how Android screenshots work.",
        ).forEach { prompt ->
            val specs = client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            )

            assertEquals("ordinary explanation unexpectedly kept tools: $prompt", 0, specs.length())
        }
    }

    @Test
    fun turboQuantNounOnlyDiscussionsRemainOrdinaryChatInAllSupportedLanguages() {
        listOf(
            "Explain how reminder apps work.", // English
            "解释提醒应用的工作原理。", // Chinese
            "Explica cómo funcionan las aplicaciones de recordatorios.", // Spanish
            "Erkläre, wie Erinnerungs-Apps funktionieren.", // German
            "Explique como funcionam os aplicativos de lembretes.", // Portuguese
            "Explique comment fonctionnent les applications de rappel.", // French
        ).forEach { prompt ->
            val specs = client.initialToolSpecsFor(
                userText = prompt,
                mode = "small",
                providerId = "llama.cpp",
                llamaCppRuntimeLane = "turboquant",
            )

            assertEquals("noun-only discussion unexpectedly kept tools: $prompt", 0, specs.length())
        }
    }

    @Test
    fun naturalEnglishPwdRequestExposesTerminalTool() {
        val specs = client.compactToolSpecsFor("Could you please run pwd and tell me the current directory?")

        assertTrue(toolNames(specs).contains("terminal_tool"))
        assertEquals(
            "pwd",
            NativeToolCallingChatClient.extractExactTerminalCommand(
                "Could you please run pwd and tell me the current directory?",
            ),
        )
    }

    @Test
    fun naturalTerminalFallbackOnlyMapsFixedReadOnlyIntents() {
        assertEquals("whoami", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Tell me the current user"))
        assertEquals("ls -la", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Please list files here"))
        assertEquals(
            "date",
            NativeToolCallingChatClient.inferSafeNaturalTerminalCommand(
                "Run a command to tell me what time it is.",
            ),
        )
        assertEquals("date", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("What time is it?"))
        assertEquals("date", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("What's the time?"))
        assertEquals("date", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("What is the current date?"))
        listOf(
            "Run the date command and tell me the time.",
            "运行 date 命令并告诉我时间。",
            "Ejecuta date y dime la hora.",
            "Führe date aus und nenne mir die Uhrzeit.",
            "Execute date e diga a hora.",
            "Exécute date et donne-moi l’heure.",
        ).forEach { prompt ->
            assertEquals("date", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand(prompt))
        }
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Delete every file here"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("I like the current directory layout"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Tell me what time the meeting starts"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Tell me the time the meeting starts"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("What is the time signature of this song?"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Write a poem about time"))
        assertEquals(
            null,
            NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("What is the date of the Battle of Hastings?"),
        )
        assertEquals(
            null,
            NativeToolCallingChatClient.inferSafeNaturalTerminalCommand(
                "Inside the active Alpine 3.21 guest, perform this as one guest action: pwd",
            ),
        )
        assertEquals(
            null,
            NativeToolCallingChatClient.inferSafeNaturalTerminalCommand(
                "Inside the active Alpine 3.21 guest, run uname -a and whoami",
            ),
        )
        assertTrue(
            NativeToolCallingChatClient.isGuestLinuxSandboxIntent(
                "Inside the active Alpine 3.21 guest, perform this as one guest action: date +%Y",
            ),
        )
    }

    @Test
    fun compactToolSpecsIncludeMemoryAliasesForRecallPrompt() {
        val specs = client.compactToolSpecsFor(
            "memory_search query=\"alpine sandbox\"",
        )
        val names = toolNames(specs)

        assertEquals(listOf("memory_search"), names)
    }

    @Test
    fun compactToolSpecsHonorExplicitLinuxSandboxToolRequest() {
        val specs = client.compactToolSpecsFor(
            "Call linux_sandbox_tool with action=deploy and distro_id=alpine-3-21.",
        )
        val names = toolNames(specs)

        assertEquals(listOf("linux_sandbox_tool"), names)
    }

    @Test
    fun activeAlpineCommandUsesOnlySmallRunAliasSchema() {
        val specs = client.compactToolSpecsFor(
            "Inside the active Alpine 3.21 guest, perform this as one guest action: " +
                "printf 'HERMES_GEMMA_ALPINE_TOOL_OK\\n' | tee /tmp/hermes-gemma-alpine-proof; " +
                "cat /etc/alpine-release | tee -a /tmp/hermes-gemma-alpine-proof",
        )
        assertEquals(listOf("mcp_run_in_proot"), toolNames(specs))

        val prompt = NativeToolCallingChatClient.buildFocusedSystemPromptContent(
            toolNames = setOf("mcp_run_in_proot"),
        )
        assertTrue(prompt.length < 600)
        assertTrue(prompt.contains("installed Linux guest"))
    }

    @Test
    fun focusedPromptTellsSmallModelsToolsAreActuallyAvailable() {
        val prompt = NativeToolCallingChatClient.buildFocusedSystemPromptContent(setOf("terminal_tool"))

        assertTrue(prompt.contains("Tools are available"))
        assertTrue(prompt.contains("instead of saying you cannot execute commands"))
        assertTrue(prompt.contains("<tool_call>"))
    }

    @Test
    fun parsesMiniCpmTaggedJsonToolCallFallback() {
        val calls = NativeToolCallingChatClient.parseToolCallContentForTest(
            "<|tool_call_start|>[{\"name\":\"terminal_tool\",\"arguments\":{\"command\":\"pwd\"}}]<|tool_call_end|>",
        )

        assertEquals(1, calls.size)
        assertEquals("terminal_tool", calls.single().first)
        assertTrue(calls.single().second.contains("pwd"))
    }

    @Test
    fun parsesFencedFunctionJsonFallback() {
        val calls = NativeToolCallingChatClient.parseToolCallContentForTest(
            "```json\n{\"function\":{\"name\":\"mcp_run_in_proot\",\"arguments\":\"{\\\"command\\\":\\\"uname -a\\\"}\"}}\n```",
        )

        assertEquals("mcp_run_in_proot", calls.single().first)
        assertTrue(calls.single().second.contains("uname -a"))
    }

    @Test
    fun thinkBlockIsSeparatedFromVisibleAnswer() {
        val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(
            "<think>I should inspect the directory.</think>There are three files.",
        )

        assertEquals("I should inspect the directory.", reasoning)
        assertEquals("There are three files.", answer)
    }

    @Test
    fun malformedNanbeigeOrphanCloseKeepsOneVisibleAnswer() {
        val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(
            "NANBEIGE_OK\n</think>\n\nNANBEIGE_OK",
        )

        assertEquals("NANBEIGE_OK", reasoning)
        assertEquals("NANBEIGE_OK", answer)
    }

    @Test
    fun nonDuplicatedPhysicalOrphanCloseRemainsLiteralText() {
        val content = "I should inspect the directory.\n</think>\n\nThere are three files."
        val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(
            content,
        )

        assertEquals("", reasoning)
        assertEquals(content, answer)
    }

    @Test
    fun unmatchedThinkTagsRemainLiteralText() {
        val closing = "The useful answer.</ThInK>"
        val opening = "<ThInK>Visible answer without a matching close."

        val (closingReasoning, closingAnswer) = NativeToolCallingChatClient.parseReasoningContentForTest(closing)
        val (openingReasoning, openingAnswer) = NativeToolCallingChatClient.parseReasoningContentForTest(opening)

        assertEquals("", closingReasoning)
        assertEquals(closing, closingAnswer)
        assertEquals("", openingReasoning)
        assertEquals(opening, openingAnswer)
    }

    @Test
    fun thinkParserPreservesLiteralFencedAndMultipleCloseText() {
        val cases = listOf(
            "Explain the literal <think>draft</think> tag.",
            "<think>literal-only block</think>",
            "first</think>middle</think>last",
            "NANBEIGE_OK\n</think>\n\nNANBEIGE_OK\n</think>\n\nNANBEIGE_OK",
            """
                ```xml
                NANBEIGE_OK
                </think>

                NANBEIGE_OK
                ```
            """.trimIndent(),
        )

        cases.forEach { content ->
            val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(content)

            assertEquals("literal content unexpectedly became reasoning: $content", "", reasoning)
            assertEquals("literal content was rewritten: $content", content, answer)
        }
    }

    @Test
    fun multipleLeadingThinkBlocksRemainSupported() {
        val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(
            "<think>First thought.</think>\n<think>Second thought.</think>\nVisible answer.",
        )

        assertEquals("First thought.\nSecond thought.", reasoning)
        assertEquals("Visible answer.", answer)
    }

    @Test
    fun thinkLiteralInsideFencedToolCallStillParsesAsToolArguments() {
        val calls = NativeToolCallingChatClient.parseToolCallContentForTest(
            """
                ```json
                {"name":"file_write_tool","arguments":{"path":"note.txt","content":"Keep </think> literally"}}
                ```
            """.trimIndent(),
        )

        assertEquals(1, calls.size)
        assertEquals("file_write_tool", calls.single().first)
        assertEquals(
            "Keep </think> literally",
            org.json.JSONObject(calls.single().second).getString("content"),
        )
    }

    private fun toolNames(specs: org.json.JSONArray): List<String> = buildList {
        for (index in 0 until specs.length()) {
            add(specs.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }
}
