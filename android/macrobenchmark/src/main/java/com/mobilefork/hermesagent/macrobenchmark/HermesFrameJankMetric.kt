package com.mobilefork.hermesagent.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric.CaptureInfo
import androidx.benchmark.macro.Metric.Measurement
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.benchmark.traceprocessor.processNameLikePkg
import androidx.test.platform.app.InstrumentationRegistry

internal const val HERMES_BENCHMARK_ITERATIONS = 5
private const val TARGET_PROCESS_PREDICATE_PLACEHOLDER =
    "__HERMES_TARGET_PROCESS_SQL_PREDICATE__"

internal fun requireSingleTargetProcessPlaceholder(template: String): String {
    val first = template.indexOf(TARGET_PROCESS_PREDICATE_PLACEHOLDER)
    val duplicate = template.indexOf(
        TARGET_PROCESS_PREDICATE_PLACEHOLDER,
        first + TARGET_PROCESS_PREDICATE_PLACEHOLDER.length,
    )
    check(first >= 0 && duplicate < 0) {
        "Hermes frame metric SQL must contain exactly one target placeholder"
    }
    return template
}

private val frameMetricQueryTemplate: String by lazy {
    InstrumentationRegistry.getInstrumentation().context.assets
        .open("hermes_frame_jank_metric.sql")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
        .let(::requireSingleTargetProcessPlaceholder)
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
        val selfJankTaggedFrames = row.long("self_jank_tagged_frames")
        val appDeadlineMissedFrames = row.long("app_deadline_missed_frames")
        val nonDeadlineSelfJankTaggedFrames =
            row.long("non_deadline_self_jank_tagged_frames")
        val otherJankTaggedFrames = row.long("other_jank_tagged_frames")
        val overlappingJankTagFrames = row.long("overlapping_jank_tag_frames")
        val droppedFrames = row.long("dropped_frames")
        val unknownTagFrames = row.long("unknown_tag_frames")
        val selfJankTaggedPercent = if (totalFrames == 0L) {
            0.0
        } else {
            selfJankTaggedFrames.toDouble() * 100.0 / totalFrames.toDouble()
        }

        check(totalFrames >= 0 && selfJankTaggedFrames >= 0 &&
            appDeadlineMissedFrames >= 0 && nonDeadlineSelfJankTaggedFrames >= 0 &&
            otherJankTaggedFrames >= 0 && overlappingJankTagFrames >= 0 &&
            droppedFrames >= 0 && unknownTagFrames >= 0) {
            "Perfetto returned a negative Hermes frame count"
        }
        check(selfJankTaggedFrames <= totalFrames &&
            appDeadlineMissedFrames + nonDeadlineSelfJankTaggedFrames == selfJankTaggedFrames &&
            otherJankTaggedFrames <= totalFrames &&
            selfJankTaggedFrames + otherJankTaggedFrames <= totalFrames &&
            overlappingJankTagFrames == 0L &&
            droppedFrames <= totalFrames &&
            unknownTagFrames <= totalFrames) {
            "Perfetto returned Hermes frame categories outside the total frame count"
        }
        return listOf(
            Measurement("hermesFrameTotalCount", totalFrames.toDouble()),
            Measurement(
                "hermesFrameSelfJankTaggedCount",
                selfJankTaggedFrames.toDouble(),
            ),
            Measurement(
                "hermesFrameAppDeadlineMissedCount",
                appDeadlineMissedFrames.toDouble(),
            ),
            Measurement(
                "hermesFrameNonDeadlineSelfJankTaggedCount",
                nonDeadlineSelfJankTaggedFrames.toDouble(),
            ),
            Measurement(
                "hermesFrameOtherJankTaggedCount",
                otherJankTaggedFrames.toDouble(),
            ),
            Measurement(
                "hermesFrameOverlappingJankTagCount",
                overlappingJankTagFrames.toDouble(),
            ),
            Measurement("hermesFrameDroppedCount", droppedFrames.toDouble()),
            Measurement("hermesFrameUnknownTagCount", unknownTagFrames.toDouble()),
            Measurement("hermesFrameSelfJankTaggedPercent", selfJankTaggedPercent),
            Measurement("hermesEvidenceToken", evidenceToken.toDouble()),
        )
    }
}
