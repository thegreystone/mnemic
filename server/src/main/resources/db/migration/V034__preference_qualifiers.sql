-- A qualifier on prefers ('for anything urgent, over email'), dislikes, uses, decided, or considering was stored
-- but never shown: the seed templates had no slot for it, and every such remember warned about it (usage bench,
-- 2026-09-23). The templates gain the slot, as related_to and knows did in V013; a template a user already
-- corrected is left alone. The engine re-renders every fact after a migration. Never edit an applied migration.

UPDATE predicate SET render = '{subject} prefers {object}[[ ({qualifier})]]' WHERE name = 'prefers' AND seed = 1 AND render = '{subject} prefers {object}';
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('prefers', 'render', '{subject} prefers {object}', '{subject} prefers {object}[[ ({qualifier})]]', 'V034: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
UPDATE predicate SET render = '{subject} dislikes {object}[[ ({qualifier})]]' WHERE name = 'dislikes' AND seed = 1 AND render = '{subject} dislikes {object}';
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('dislikes', 'render', '{subject} dislikes {object}', '{subject} dislikes {object}[[ ({qualifier})]]', 'V034: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
UPDATE predicate SET render = '{subject} uses {object}[[ ({qualifier})]]' WHERE name = 'uses' AND seed = 1 AND render = '{subject} uses {object}';
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('uses', 'render', '{subject} uses {object}', '{subject} uses {object}[[ ({qualifier})]]', 'V034: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
UPDATE predicate SET render = '{subject} decided {object}[[ ({qualifier})]]' WHERE name = 'decided' AND seed = 1 AND render = '{subject} decided {object}';
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('decided', 'render', '{subject} decided {object}', '{subject} decided {object}[[ ({qualifier})]]', 'V034: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
UPDATE predicate SET render = '{subject} is considering {object}[[ ({qualifier})]]' WHERE name = 'considering' AND seed = 1 AND render = '{subject} is considering {object}';
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('considering', 'render', '{subject} is considering {object}', '{subject} is considering {object}[[ ({qualifier})]]', 'V034: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
