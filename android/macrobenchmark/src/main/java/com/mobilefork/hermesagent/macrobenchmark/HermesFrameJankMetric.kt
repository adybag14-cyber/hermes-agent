package com.mobilefork.hermesagent.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric.CaptureInfo
import androidx.benchmark.macro.Metric.Measurement
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.benchmark.traceprocessor.processNameLikePkg
import androidx.test.platform.app.InstrumentationRegistry

internal const val HERMES_BENCHMARK_ITERATIONS = 5
internal const val HERMES_MIN_AGGREGATE_FRAMES = 100
internal const val HERMES_MAX_AGGREGATE_JANK_PERCENT = 10.0
private const val TARGET_PROCESS_PREDICATE_PLACEHOLDER =
    "__HERMES_TARGET_PROCESS_SQL_PREDICATE__"

private val frameMetricQueryTemplate: String by lazy {
    InstrumentationRegistry.getInstrumentation().context.assets
        .open("hermes_frame_jank_metric.sql")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}

@OptIn(ExperimentalMetricApi::class)
internal class HermesFrameJankMetric(
    private val evidenceToken: Long,
) : TraceMetric() {
    init {
        require(evidenceToken in 0 until (1L shl 53)) {
            "Hermes evidence token must be an exactly representable nonnegative integer"
        }
    }

    override fun getMeasurements(
        captureInfo: CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Measurement> {
        val targetProcessPredicate = processNameLikePkg(captureInfo.targetPackageName)
        val query = frameMetricQueryTemplate.replace(
            TARGET_PROCESS_PREDICATE_PLACEHOLDER,
            targetProcessPredicate,
        )
        check(TARGET_PROCESS_PREDICATE_PLACEHOLDER !in query) {
            "Hermes frame metric SQL target was not bound"
        }

        val row = traceSession.query(query).firstOrNull()
            ?: error("Perfetto returned no Hermes frame aggregate")
        val totalFrames = row.long("total_frames")
        val jankyFrames = row.long("janky_frames")
        val appDeadlineMissedFrames = row.long("app_deadline_missed_frames")
        val otherJankyFrames = row.long("other_janky_frames")
        val jankPercent = if (totalFrames == 0L) {
            0.0
        } else {
            jankyFrames.toDouble() * 100.0 / totalFrames.toDouble()
        }

        check(totalFrames >= 0 && jankyFrames >= 0 &&
            appDeadlineMissedFrames >= 0 && otherJankyFrames >= 0) {
            "Perfetto returned a negative Hermes frame count"
        }
        check(appDeadlineMissedFrames + otherJankyFrames == jankyFrames) {
            "Hermes jank categories do not reconcile: " +
                "$appDeadlineMissedFrames + $otherJankyFrames != $jankyFrames"
        }
        return listOf(
            Measurement("hermesFrameTotalCount", totalFrames.toDouble()),
            Measurement("hermesFrameJankyCount", jankyFrames.toDouble()),
            Measurement(
                "hermesFrameAppDeadlineMissedCount",
                appDeadlineMissedFrames.toDouble(),
            ),
            Measurement("hermesFrameOtherJankCount", otherJankyFrames.toDouble()),
            Measurement("hermesFrameJankPercent", jankPercent),
            Measurement("hermesEvidenceToken", evidenceToken.toDouble()),
        )
    }
}
