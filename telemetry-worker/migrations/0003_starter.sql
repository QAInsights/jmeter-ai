CREATE TABLE starter_messages (
 id TEXT PRIMARY KEY,
 install_id TEXT NOT NULL,
 ip_hash TEXT NOT NULL
);
CREATE INDEX starter_install ON starter_messages(install_id);
CREATE INDEX starter_ip ON starter_messages(ip_hash);
CREATE TABLE starter_usage (
 id INTEGER PRIMARY KEY,
 month TEXT NOT NULL,
 install_id TEXT NOT NULL,
 upstream TEXT NOT NULL,
 input_tokens INTEGER NOT NULL,
 output_tokens INTEGER NOT NULL,
 cost_usd REAL NOT NULL
);
CREATE INDEX starter_month ON starter_usage(month);
