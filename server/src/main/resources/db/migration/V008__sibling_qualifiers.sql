-- sibling_of knew brother and sister only, so twin, half-brother, half-sister, stepbrother, and stepsister were
-- warned about on every use (2026-09-09). Existing stores get the wider seed list and a change record.
-- Never edit an applied migration.

UPDATE predicate SET qualifiers = '["brother","sister","twin","twin brother","twin sister","half-brother","half-sister","stepbrother","stepsister"]'
WHERE name = 'sibling_of' AND seed = 1;
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
SELECT 'sibling_of', 'qualifiers', '["brother","sister"]',
       '["brother","sister","twin","twin brother","twin sister","half-brother","half-sister","stepbrother","stepsister"]',
       'migration V008: sibling kinds and degrees', datetime('now')
WHERE EXISTS (SELECT 1 FROM predicate WHERE name = 'sibling_of' AND seed = 1);
