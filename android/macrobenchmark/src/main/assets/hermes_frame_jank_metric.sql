WITH hermes_frames AS (
    SELECT
        frame.surface_frame_token AS frame_token,
        MAX(
            CASE
                WHEN LOWER(TRIM(COALESCE(frame.jank_type, ''))) NOT IN ('', 'none')
                THEN 1 ELSE 0
            END
        ) AS is_janky,
        MAX(
            CASE
                WHEN INSTR(
                    LOWER(COALESCE(frame.jank_type, '')),
                    'app deadline missed'
                ) > 0
                THEN 1 ELSE 0
            END
        ) AS is_app_deadline_missed
    FROM actual_frame_timeline_slice AS frame
    INNER JOIN process ON process.upid = frame.upid
    WHERE __HERMES_TARGET_PROCESS_SQL_PREDICATE__
      AND frame.surface_frame_token > 0
      AND frame.dur > 0
    GROUP BY frame.surface_frame_token
)
SELECT
    COUNT(*) AS total_frames,
    COALESCE(SUM(is_janky), 0) AS janky_frames,
    COALESCE(SUM(is_app_deadline_missed), 0) AS app_deadline_missed_frames,
    COALESCE(
        SUM(
            CASE
                WHEN is_janky = 1 AND is_app_deadline_missed = 0
                THEN 1 ELSE 0
            END
        ),
        0
    ) AS other_janky_frames
FROM hermes_frames
