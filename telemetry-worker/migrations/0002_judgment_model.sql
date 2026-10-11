-- Decisions backend for routing/triage and the chat model in use (well-known names, else "custom").
ALTER TABLE pings ADD COLUMN judgment_provider TEXT;
ALTER TABLE pings ADD COLUMN model TEXT;
