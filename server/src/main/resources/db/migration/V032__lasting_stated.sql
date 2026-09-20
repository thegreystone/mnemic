-- Whether anything ever said if a relation is lasting: the seed did, a definition or a correction did, or nobody
-- did and the store assumes. An assumption is acted on as before and said out loud where it acts: in the reply
-- to the definition, and on a fact an ending closes, with the correction that keeps such facts open.
ALTER TABLE predicate ADD COLUMN lasting_stated INTEGER NOT NULL DEFAULT 0;
UPDATE predicate SET lasting_stated = 1
WHERE seed = 1 OR name IN (SELECT DISTINCT predicate FROM predicate_change WHERE field = 'lasting');
