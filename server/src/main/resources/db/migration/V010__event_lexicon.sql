-- Event types answer questions the way predicates do: "when did Mattias buy his house" is the purchased event
-- with its date, and it had no cue at all while "buy" matched a decision not to buy something else
-- (2026-09-10). Registered types carry the terms that name them; an unregistered type matches its own name.
-- Never edit an applied migration.

ALTER TABLE event_type ADD COLUMN lexicon TEXT NOT NULL DEFAULT '[]';
UPDATE event_type SET lexicon = '["joined","join","started"]' WHERE name = 'joined' AND seed = 1;
UPDATE event_type SET lexicon = '["hired"]' WHERE name = 'hired' AND seed = 1;
UPDATE event_type SET lexicon = '["founded","found"]' WHERE name = 'founded' AND seed = 1;
UPDATE event_type SET lexicon = '["left","quit","resigned"]' WHERE name = 'left' AND seed = 1;
UPDATE event_type SET lexicon = '["retired","retire"]' WHERE name = 'retired' AND seed = 1;
UPDATE event_type SET lexicon = '["promoted","promotion"]' WHERE name = 'promoted' AND seed = 1;
UPDATE event_type SET lexicon = '["moved","move","relocated"]' WHERE name = 'moved' AND seed = 1;
UPDATE event_type SET lexicon = '["married","marry","wedding"]' WHERE name = 'married' AND seed = 1;
UPDATE event_type SET lexicon = '["divorced","divorce"]' WHERE name = 'divorced' AND seed = 1;
UPDATE event_type SET lexicon = '["born","birth","birthday"]' WHERE name = 'born' AND seed = 1;
UPDATE event_type SET lexicon = '["decided","decide","decision"]' WHERE name = 'decided' AND seed = 1;
UPDATE event_type SET lexicon = '["met","meet"]' WHERE name = 'met' AND seed = 1;
UPDATE event_type SET lexicon = '["died","death","passed"]' WHERE name = 'died' AND seed = 1;
UPDATE event_type SET lexicon = '["dissolved"]' WHERE name = 'dissolved' AND seed = 1;
