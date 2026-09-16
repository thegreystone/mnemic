-- 2.4 seeds partner_of, engaged_to, and step_parent_of (the registries insert missing seeds at start). The seed rows
-- they change are moved along only where they still read as the old seed: a row the user corrected is left alone.
UPDATE event_type SET closes = '["partner_of","engaged_to"]'
WHERE name = 'married' AND seed = 1 AND closes = '[]';
UPDATE predicate SET lexicon = '["spouse","married","marry"]'
WHERE name = 'spouse_of' AND seed = 1 AND lexicon = '["spouse","married","marry","partner"]';
UPDATE predicate SET qualifiers = '["mother","father","mom","dad"]'
WHERE name = 'parent_of' AND seed = 1 AND qualifiers = '["mother","father","stepmother","stepfather","mom","dad"]';
