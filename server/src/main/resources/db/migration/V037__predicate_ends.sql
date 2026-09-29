-- A fact of one relation can end an open fact of another about the same subject and object: a decision about a
-- thing, or owning it, ends considering it. "I'm leaning toward a Zenit 4" stayed current after "I ordered the
-- Zenit 4" and was read as still undecided (usage bench, 2026-09-29). The predicate says what it ends; nothing is
-- hard-coded about purchases, since a purchase opens owns. considering and decided take a thing as well as words,
-- so that the consideration can be about the same thing: a range of literal and '*' keeps a phrase as text and a
-- named thing as the thing. A seed row a user already changed is left alone. Never edit an applied migration.
ALTER TABLE predicate ADD COLUMN ends TEXT NOT NULL DEFAULT '[]';
UPDATE predicate SET ends = '["considering"]' WHERE name IN ('decided', 'owns') AND seed = 1;
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
SELECT name, 'ends', '[]', '["considering"]', 'V037: a decision about a thing, or owning it, ends considering it',
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM predicate WHERE name IN ('decided', 'owns') AND seed = 1;
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
SELECT name, 'range', '["literal"]', '["literal","*"]', 'V037: a plan or decision may be about a thing',
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM predicate WHERE name IN ('considering', 'decided') AND seed = 1 AND range = '["literal"]';
UPDATE predicate SET range = '["literal","*"]'
WHERE name IN ('considering', 'decided') AND seed = 1 AND range = '["literal"]';
