CREATE TABLE IF NOT EXISTS pings (
    install_id TEXT NOT NULL,
    day TEXT NOT NULL,
    ts INTEGER NOT NULL,
    event TEXT,
    plugin_version TEXT,
    jmeter_version TEXT,
    java_version TEXT,
    os TEXT,
    arch TEXT,
    provider TEXT,
    agent_enabled INTEGER,
    first_run INTEGER,
    country TEXT,
    UNIQUE(install_id, day)
);

CREATE INDEX IF NOT EXISTS idx_pings_day ON pings(day);

CREATE TABLE IF NOT EXISTS feature_counts (
    install_id TEXT NOT NULL,
    day TEXT NOT NULL,
    feature TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (install_id, day, feature)
);
