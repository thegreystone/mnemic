-- Relation cues have a direction. "Mattias's father" names the parent (Mattias is the object of parent_of);
-- "Mattias's children" names the other end and had no cue at all, so the same six facts were unreachable from
-- one phrasing and returned in both directions from the other (2026-09-10). A predicate now carries the
-- terms that name its object side. Never edit an applied migration.

ALTER TABLE predicate ADD COLUMN inverse_lexicon TEXT NOT NULL DEFAULT '[]';
UPDATE predicate SET inverse_lexicon = '["child","children","kid","kids","son","sons","daughter","daughters","offspring"]'
WHERE name = 'parent_of' AND seed = 1;
UPDATE predicate SET inverse_lexicon = '["employee","employees","staff"]' WHERE name = 'works_at' AND seed = 1;
UPDATE predicate SET inverse_lexicon = '["contains","within it","in it"]' WHERE name = 'located_in' AND seed = 1;
