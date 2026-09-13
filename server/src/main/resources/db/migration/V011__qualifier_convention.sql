-- The qualifier of a relation describes the subject: sibling_of(Clara, self, half-sister) says Clara is the
-- half-sister. Nothing said so, and a fact went in backwards (2026-09-10). The seed descriptions now say it.
-- Never edit an applied migration.

UPDATE predicate SET description = 'Subject is a parent of object, and the qualifier names the subject''s role (mother, father).' WHERE name = 'parent_of' AND seed = 1;
UPDATE predicate SET description = 'Subject is married to object, and the qualifier names the subject''s role (wife, husband). Stored once, found from both sides.' WHERE name = 'spouse_of' AND seed = 1;
UPDATE predicate SET description = 'Subject is a sibling of object, and the qualifier names the subject''s role (brother, half-sister). Stored once, found from both sides.' WHERE name = 'sibling_of' AND seed = 1;
