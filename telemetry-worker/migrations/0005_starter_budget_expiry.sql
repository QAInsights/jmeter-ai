ALTER TABLE starter_budget_reservations ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0;
CREATE INDEX starter_budget_expiry ON starter_budget_reservations(month, expires_at);
