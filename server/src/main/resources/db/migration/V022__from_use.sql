-- Before 2.3 an undefined predicate was stored as x:<name>, reachable lexically only, and an event of a type
-- nobody registered kept whatever spelling the caller used. Registering from use replaced both: the prefix goes
-- where the bare name is free, and event types are spelled lowercase with underscores everywhere. The lexicon a
-- renamed predicate lacks, and the registrations for event and entity types found in use, are filled by the
-- registries at the next start, since SQL cannot split a name into words.
PRAGMA defer_foreign_keys = ON;

UPDATE predicate SET name = substr(name, 3)
WHERE name LIKE 'x:%' AND NOT EXISTS (SELECT 1 FROM predicate p WHERE p.name = substr(predicate.name, 3));
UPDATE fact SET predicate = substr(predicate, 3)
WHERE predicate LIKE 'x:%' AND EXISTS (SELECT 1 FROM predicate p WHERE p.name = substr(fact.predicate, 3));
UPDATE predicate_render SET name = substr(name, 3)
WHERE name LIKE 'x:%' AND EXISTS (SELECT 1 FROM predicate p WHERE p.name = substr(predicate_render.name, 3));
UPDATE predicate_change SET predicate = substr(predicate, 3)
WHERE predicate LIKE 'x:%' AND EXISTS (SELECT 1 FROM predicate p WHERE p.name = substr(predicate_change.predicate, 3));
UPDATE question SET predicate = substr(predicate, 3)
WHERE predicate LIKE 'x:%' AND EXISTS (SELECT 1 FROM predicate p WHERE p.name = substr(question.predicate, 3));

UPDATE event SET type = replace(lower(trim(type)), ' ', '_') WHERE type <> replace(lower(trim(type)), ' ', '_');
UPDATE event_type SET name = replace(lower(trim(name)), ' ', '_')
WHERE name <> replace(lower(trim(name)), ' ', '_')
  AND NOT EXISTS (SELECT 1 FROM event_type t WHERE t.name = replace(lower(trim(event_type.name)), ' ', '_'));
