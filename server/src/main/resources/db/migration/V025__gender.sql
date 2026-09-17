-- An entity may carry a gender of its own (female, male, or the caller's word), which the kinship rules read ahead
-- of the roles it holds. The seed sibling rule grew a half-sibling tier; a store that still has the first seed takes
-- the new one at start.
ALTER TABLE entity ADD COLUMN gender TEXT;
UPDATE predicate SET rule = NULL WHERE name = 'sibling_of' AND seed = 1
  AND rule = '[{"path":["^parent_of","parent_of"],"qualifier":"sibling","by_gender":{"female":"sister","male":"brother"}}]';
