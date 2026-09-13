-- located_in was seeded as functional (one current value), which rejects "Schübelbach is located in
-- Kanton Schwyz" once "… located in Switzerland" is known: places nest, this is containment, not a change of
-- address (2026-09-09). Existing stores get the corrected seed definition and a change record.
-- Never edit an applied migration.

UPDATE predicate SET functional = 0,
    description = 'Subject place or organization is located in object place, and places nest (a town in a canton in a country), so several current values are expected.'
WHERE name = 'located_in' AND seed = 1 AND functional = 1;
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
SELECT 'located_in', 'functional', 'true', 'false', 'migration V007: containment nests', datetime('now')
WHERE EXISTS (SELECT 1 FROM predicate WHERE name = 'located_in' AND seed = 1);
