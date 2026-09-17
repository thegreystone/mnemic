-- Containment is a property of a predicate, not two names in the code: a containment predicate's facts nest the
-- subject inside the object, and bounds, closures, and containment questions are checked along them. Whether two
-- things of a kind can overlap is a property of the entity type. The seeds keep what they had.
ALTER TABLE predicate ADD COLUMN containment INTEGER NOT NULL DEFAULT 0;
UPDATE predicate SET containment = 1 WHERE name IN ('located_in', 'part_of');
ALTER TABLE entity_type ADD COLUMN disjoint INTEGER NOT NULL DEFAULT 0;
UPDATE entity_type SET disjoint = 1 WHERE name = 'country';
