-- Aggregate input/output token usage since the install's previous successful ping.
ALTER TABLE pings ADD COLUMN input_tokens INTEGER;
ALTER TABLE pings ADD COLUMN output_tokens INTEGER;
