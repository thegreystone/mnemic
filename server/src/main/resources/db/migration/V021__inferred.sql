-- Whether a term was registered from its first use and nobody has said anything about it since. Kept as a flag
-- rather than read off a missing description, so that a partial definition (functional, range) counts as one.
ALTER TABLE predicate ADD COLUMN inferred INTEGER NOT NULL DEFAULT 0;
ALTER TABLE event_type ADD COLUMN inferred INTEGER NOT NULL DEFAULT 0;
ALTER TABLE entity_type ADD COLUMN inferred INTEGER NOT NULL DEFAULT 0;

UPDATE predicate SET inferred = 1 WHERE seed = 0 AND description IS NULL;
UPDATE event_type SET inferred = 1 WHERE seed = 0 AND description IS NULL;
UPDATE entity_type SET inferred = 1 WHERE seed = 0 AND description IS NULL;
