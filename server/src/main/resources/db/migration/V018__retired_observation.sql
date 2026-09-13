-- A retired observation (2026-09-12, D8): recorded wrongly or superseded by a later one, kept with its text and
-- history, out of recall unless history is asked for, out of the proposal backlog, its facts untouched (they are
-- retracted or corrected on their own). Distinct from forget, which removes. Never edit an applied migration.

ALTER TABLE observation
    ADD COLUMN retired_at TEXT;
ALTER TABLE observation
    ADD COLUMN retired_reason TEXT;
ALTER TABLE observation
    ADD COLUMN superseded_by INTEGER REFERENCES observation (id) ON DELETE SET NULL;
