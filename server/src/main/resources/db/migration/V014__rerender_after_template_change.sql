-- V013 changed two seed templates but left every existing rendering as it was, so facts written before it kept
-- reading 'X is related to Y' with their qualifier hidden in the row (2026-09-10). From this version on the
-- engine re-renders every fact after any migration is applied; this one records the change in the audit log.
-- Never edit an applied migration.

INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('related_to', 'render', '{subject} is related to {object}', '{subject} is related to {object}[[ ({qualifier})]]', 'V013: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
INSERT INTO predicate_change(predicate, field, old_value, new_value, reason, changed_at)
VALUES ('knows', 'render', '{subject} knows {object}', '{subject} knows {object}[[ ({qualifier})]]', 'V013: the qualifier was dropped from the rendering', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
