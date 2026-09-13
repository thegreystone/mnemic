-- Hybrid mode: a proposal may come from the assistant that called remember or from a model the server was
-- configured with (mnemic.proposer.model). Which one is provenance, so recall annotations and history can say
-- whether a fact is the assistant's reading or the configured model's. NULL means the assistant (or no proposal).
-- Never edit an applied migration.

ALTER TABLE observation ADD COLUMN proposer TEXT;
