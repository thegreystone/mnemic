-- related_to is the catch-all a caller reaches for when nothing else fits, so its qualifier carries the most
-- meaning, and its template had no slot for it: 'believed to be the same company, converted from HB to AB' was
-- dropped without a word (2026-09-10). Never edit an applied migration.

UPDATE predicate SET render = '{subject} is related to {object}[[ ({qualifier})]]' WHERE name = 'related_to' AND seed = 1;
UPDATE predicate SET render = '{subject} knows {object}[[ ({qualifier})]]' WHERE name = 'knows' AND seed = 1;
