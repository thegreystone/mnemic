-- Family Q (EVALUATION.md, 2026-09-10): a fact row can say that a relation holds (asserted), that it does not
-- (negated), that everything the subject has under the predicate lies within a place (only), or that the recorded
-- facts of a class are all of them (closure). Until now only the first was representable, and 'I own nothing in
-- Sweden' and 'those are all the properties I own' had no home. Never edit an applied migration.

ALTER TABLE fact ADD COLUMN mode TEXT NOT NULL DEFAULT 'asserted';
CREATE INDEX IF NOT EXISTS fact_mode ON fact(subject_id, predicate, mode);
