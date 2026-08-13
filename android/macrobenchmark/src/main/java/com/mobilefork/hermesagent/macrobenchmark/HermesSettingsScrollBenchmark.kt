package com.mobilefork.hermesagent.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val UI_TIMEOUT_MILLIS = 20_000L
private const val FLING_CYCLES_PER_ITERATION = 5
private const val MAX_RESET_FLINGS = 20

@LargeTest
@RunWith(AndroidJUnit4::class)
class HermesSettingsScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun settingsListFling() {
        val targetIdentity = verifyInstalledTargetIdentity()

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                HermesFrameJankMetric(targetIdentity.evidenceToken),
            ),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = HERMES_BENCHMARK_ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                navigateToSettingsList()
                resetListToTop()
            },
        ) {
            repeat(FLING_CYCLES_PER_ITERATION) {
                flingSettingsList(Direction.DOWN)
                flingSettingsList(Direction.UP)
            }
        }
    }

    private fun navigateToSettingsList() {
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MILLIS)) {
            "Hermes did not become the foreground package"
        }
        if (findSettingsList() == null) {
            val tabletSettings = device.findObject(By.res(TABLET_SETTINGS_TAG))
            if (tabletSettings != null) {
                requireOwnedByTarget(tabletSettings, TABLET_SETTINGS_TAG).click()
            } else {
                val drawer = requireObject(CHAT_DRAWER_TAG)
                drawer.click()
                requireObject(PHONE_SETTINGS_TAG).click()
            }
            requireObject(SETTINGS_CONTENT_TAG)
        }

        selectModelsSettingsPage()
        requireSettingsList()
        device.waitForIdle()
    }

    private fun selectModelsSettingsPage() {
        val modelsPage = requireObject(SETTINGS_MODELS_PAGE_TAG)
        if (modelsPage.isEnabled) {
            modelsPage.click()
        }
        val selectedModelsPage = device.wait(
            Until.findObject(By.res(SETTINGS_MODELS_PAGE_TAG).enabled(false)),
            UI_TIMEOUT_MILLIS,
        ) ?: error("Hermes Models settings page did not become selected")
        requireOwnedByTarget(selectedModelsPage, SETTINGS_MODELS_PAGE_TAG)
        check(!selectedModelsPage.isEnabled) {
            "$SETTINGS_MODELS_PAGE_TAG must be disabled while selected"
        }
    }

    private fun resetListToTop() {
        repeat(MAX_RESET_FLINGS) {
            val list = requireSettingsList()
            list.setGestureMargin(device.displayWidth / 10)
            val canContinueTowardTop = list.fling(Direction.UP)
            device.waitForIdle()
            if (!canContinueTowardTop) return
        }
        error("$SETTINGS_CONTENT_TAG did not reach the top after $MAX_RESET_FLINGS flings")
    }

    private fun flingSettingsList(direction: Direction) {
        val list = requireSettingsList()
        list.setGestureMargin(device.displayWidth / 10)
        list.fling(direction)
        device.waitForIdle()
    }

    private fun findSettingsList(): UiObject2? =
        device.findObject(By.res(SETTINGS_CONTENT_TAG))?.let { list ->
            requireOwnedByTarget(list, SETTINGS_CONTENT_TAG)
            list
        }

    private fun requireSettingsList(): UiObject2 {
        val list = device.wait(
            Until.findObject(By.res(SETTINGS_CONTENT_TAG)),
            UI_TIMEOUT_MILLIS,
        ) ?: error("Hermes Settings list did not appear")
        requireOwnedByTarget(list, SETTINGS_CONTENT_TAG)
        check(list.isScrollable) { "$SETTINGS_CONTENT_TAG is not scrollable" }
        return list
    }

    private fun requireObject(resourceId: String): UiObject2 {
        val node = device.wait(Until.findObject(By.res(resourceId)), UI_TIMEOUT_MILLIS)
            ?: error("Hermes UI node did not appear: $resourceId")
        return requireOwnedByTarget(node, resourceId)
    }

    private fun requireOwnedByTarget(node: UiObject2, resourceId: String): UiObject2 {
        check(node.applicationPackage == TARGET_PACKAGE) {
            "$resourceId belongs to ${node.applicationPackage}, not $TARGET_PACKAGE"
        }
        return node
    }
}
