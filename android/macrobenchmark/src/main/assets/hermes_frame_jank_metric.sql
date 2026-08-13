WITH hermes_frames AS (
    SELECT
        frame.surface_frame_token AS frame_token,
        -- Keep Perfetto's visualization tags as attribution diagnostics.
        -- Prediction Error and Buffer Stuffing may mark every emulator frame's
        -- jank_type while the tag remains Other Jank. These surface-token tags
        -- and AndroidX FrameTiming samples are distinct populations; the host
        -- validator records and labels them separately.
        MAX(
            CASE
                WHEN frame.jank_tag = 'Self Jank'
                THEN 1 ELSE 0
            END
        ) AS is_self_jank_tagged,
        MAX(
            CASE
                WHEN INSTR(
                    LOWER(COALESCE(frame.jank_type, '')),
                    'app deadline missed'
                ) > 0
                THEN 1 ELSE 0
            END
        ) AS is_app_deadline_missed,
        MAX(
            CASE
                WHEN frame.jank_tag = 'Other Jank'
                THEN 1 ELSE 0
            END
        ) AS is_other_jank_tagged,
        MAX(
            CASE
                WHEN frame.jank_tag = 'Dropped Frame'
                THEN 1 ELSE 0
            END
        ) AS is_dropped,
        MAX(
            CASE
                WHEN frame.jank_tag IN (
                    'Self Jank',
                    'Other Jank',
                    'Dropped Frame',
                    'Buffer Stuffing',
                    'SurfaceFlinger Stuffing',
                    'No Jank'
                )
                THEN 0 ELSE 1
            END
        ) AS has_unknown_tag
    FROM actual_frame_timeline_slice AS frame
    INNER JOIN process ON process.upid = frame.upid
    WHERE __HERMES_TARGET_PROCESS_SQL_PREDICATE__
      AND frame.surface_frame_token > 0
      AND frame.dur > 0
    GROUP BY frame.surface_frame_token
)
SELECT
    COUNT(*) AS total_frames,
    COALESCE(SUM(is_self_jank_tagged), 0) AS self_jank_tagged_frames,
    COALESCE(SUM(is_app_deadline_missed), 0) AS app_deadline_missed_frames,
    COALESCE(
        SUM(
            CASE
                WHEN is_app_deadline_missed = 1 OR is_dropped = 1
                THEN 1 ELSE 0
            END
        ),
        0
    ) AS app_deadline_missed_or_dropped_frames,
    COALESCE(
        SUM(
            CASE
                WHEN is_self_jank_tagged = 1 AND is_app_deadline_missed = 0
                THEN 1 ELSE 0
            END
        ),
        0
    ) AS non_deadline_self_jank_tagged_frames,
    COALESCE(SUM(is_other_jank_tagged), 0) AS other_jank_tagged_frames,
    COALESCE(
        SUM(
            CASE
                WHEN is_self_jank_tagged = 1 AND is_other_jank_tagged = 1
                THEN 1 ELSE 0
            END
        ),
        0
    ) AS overlapping_jank_tag_frames,
    COALESCE(SUM(is_dropped), 0) AS dropped_frames,
    COALESCE(SUM(has_unknown_tag), 0) AS unknown_tag_frames
FROM hermes_frames
