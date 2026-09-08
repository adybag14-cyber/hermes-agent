CREATE TABLE IF NOT EXISTS reports (
    id TEXT PRIMARY KEY,
    reason TEXT NOT NULL,
    message TEXT NOT NULL,
    notes TEXT NOT NULL,
    app_version TEXT NOT NULL,
    edition TEXT NOT NULL,
    deletion_hash TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS reports_expiry ON reports(expires_at);
CREATE TABLE IF NOT EXISTS report_rate_limits (
    bucket TEXT PRIMARY KEY,
    hits INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
