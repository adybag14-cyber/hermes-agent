package com.mobilefork.hermesagent

import com.mobilefork.hermesagent.backend.BackendKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLocalRuntimeAutoStarterTest {
    @Test
    fun `each supported local backend starts exactly once on activity creation`() {
        listOf(BackendKind.LLAMA_CPP, BackendKind.LITERT_LM).forEach { selectedBackend ->
            val pending = mutableListOf<() -> Unit>()
            val requestedStarts = mutableListOf<Pair<BackendKind, Long>>()
            val autoStarter = MainActivityLocalRuntimeAutoStarter(
                loadSelectedBackend = { selectedBackend },
                captureSelectionGeneration = { 41L },
                launchAsync = { action -> pending += action },
                ensureStarted = { expectedBackend, selectionGeneration ->
                    requestedStarts += expectedBackend to selectionGeneration
                },
            )

            autoStarter.requestAfterFirstFrame()

            assertEquals(1, pending.size)
            assertEquals(emptyList<Pair<BackendKind, Long>>(), requestedStarts)
            pending.single().invoke()
            assertEquals(listOf(selectedBackend to 41L), requestedStarts)
        }
    }

    @Test
    fun `remote selection never starts a local runtime`() {
        val pending = mutableListOf<() -> Unit>()
        var startCount = 0
        val autoStarter = MainActivityLocalRuntimeAutoStarter(
            loadSelectedBackend = { BackendKind.NONE },
            captureSelectionGeneration = { 1L },
            launchAsync = { action -> pending += action },
            ensureStarted = { _, _ -> startCount += 1 },
        )

        autoStarter.requestAfterFirstFrame()

        assertEquals(1, pending.size)
        pending.single().invoke()
        assertEquals(0, startCount)
    }

    @Test
    fun `legacy unsupported local selection never starts a runtime`() {
        val pending = mutableListOf<() -> Unit>()
        var startCount = 0
        val autoStarter = MainActivityLocalRuntimeAutoStarter(
            loadSelectedBackend = { BackendKind.AICORE },
            captureSelectionGeneration = { 1L },
            launchAsync = { action -> pending += action },
            ensureStarted = { _, _ -> startCount += 1 },
        )

        autoStarter.requestAfterFirstFrame()

        assertEquals(1, pending.size)
        pending.single().invoke()
        assertEquals(0, startCount)
    }

    @Test
    fun `repeated lifecycle or recomposition callbacks cannot duplicate startup ownership`() {
        val pending = mutableListOf<() -> Unit>()
        var generationCaptures = 0
        var selectedBackendLoads = 0
        var startCount = 0
        val autoStarter = MainActivityLocalRuntimeAutoStarter(
            loadSelectedBackend = {
                selectedBackendLoads += 1
                BackendKind.LLAMA_CPP
            },
            captureSelectionGeneration = {
                generationCaptures += 1
                9L
            },
            launchAsync = { action -> pending += action },
            ensureStarted = { _, _ -> startCount += 1 },
        )

        repeat(4) {
            autoStarter.requestAfterFirstFrame()
        }

        assertEquals(4, pending.size)
        pending.forEach { it.invoke() }
        assertEquals(1, generationCaptures)
        assertEquals(1, selectedBackendLoads)
        assertEquals(1, startCount)
    }

    @Test
    fun `activity recreation cannot consume process claim before application work starts`() {
        val pending = mutableListOf<() -> Unit>()
        var currentGeneration = 12L
        var generationCaptures = 0
        val requestedStarts = mutableListOf<Pair<BackendKind, Long>>()
        val autoStarter = MainActivityLocalRuntimeAutoStarter(
            loadSelectedBackend = { BackendKind.LLAMA_CPP },
            captureSelectionGeneration = {
                generationCaptures += 1
                currentGeneration
            },
            launchAsync = { action -> pending += action },
            ensureStarted = { expectedBackend, selectionGeneration ->
                requestedStarts += expectedBackend to selectionGeneration
            },
        )

        autoStarter.requestAfterFirstFrame()
        autoStarter.requestAfterFirstFrame()

        assertEquals(2, pending.size)
        assertEquals(0, generationCaptures)
        assertEquals(emptyList<Pair<BackendKind, Long>>(), requestedStarts)
        currentGeneration = 13L
        pending.last().invoke()
        assertEquals(1, generationCaptures)
        assertEquals(listOf(BackendKind.LLAMA_CPP to 13L), requestedStarts)
        pending.first().invoke()
        assertEquals(1, generationCaptures)
        assertEquals(listOf(BackendKind.LLAMA_CPP to 13L), requestedStarts)
    }
}
