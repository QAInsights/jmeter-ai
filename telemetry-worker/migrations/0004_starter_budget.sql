CREATE TABLE starter_budget_reservations (
 id TEXT PRIMARY KEY,
 month TEXT NOT NULL,
 cost_usd REAL NOT NULL
);
CREATE INDEX starter_budget_month ON starter_budget_reservations(month);
ALTER TABLE starter_usage ADD COLUMN attempt_id TEXT;
CREATE UNIQUE INDEX starter_usage_attempt ON starter_usage(attempt_id);
