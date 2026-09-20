-- A group's cue words are one list, whatever language they are in: matching a question's words is blind to
-- language, so a per-language table bought nothing but a key and a join. The words the table held join the list.
UPDATE predicate_group
SET lexicon = (SELECT json_group_array(DISTINCT w)
               FROM (SELECT value AS w FROM json_each(predicate_group.lexicon)
                     UNION ALL
                     SELECT value AS w FROM predicate_group_render r, json_each(r.lexicon) WHERE r.name = predicate_group.name))
WHERE name IN (SELECT name FROM predicate_group_render);
DROP TABLE predicate_group_render;
