-- Event types carry a render template, like predicates do, so an event reads as a sentence rather than as
-- type(participants). Participants keep their position in the event so a re-rendering keeps subject and object
-- apart; rows from before this migration have no position and fall back to entity id order.
ALTER TABLE event_type ADD COLUMN render TEXT;
ALTER TABLE event_participant ADD COLUMN position INTEGER;

UPDATE event_type SET render = '{subject} joined {object}' WHERE name = 'joined' AND seed = 1;
UPDATE event_type SET render = '{subject} was hired by {object}' WHERE name = 'hired' AND seed = 1;
UPDATE event_type SET render = '{subject} founded {object}' WHERE name = 'founded' AND seed = 1;
UPDATE event_type SET render = '{subject} left {object}' WHERE name = 'left' AND seed = 1;
UPDATE event_type SET render = '{subject} retired from {object}' WHERE name = 'retired' AND seed = 1;
UPDATE event_type SET render = '{subject} was promoted at {object}' WHERE name = 'promoted' AND seed = 1;
UPDATE event_type SET render = '{subject} moved to {object}' WHERE name = 'moved' AND seed = 1;
UPDATE event_type SET render = '{subject} married {object}' WHERE name = 'married' AND seed = 1;
UPDATE event_type SET render = '{subject} and {object} divorced' WHERE name = 'divorced' AND seed = 1;
UPDATE event_type SET render = '{subject} was born[[ in {object}]]' WHERE name = 'born' AND seed = 1;
UPDATE event_type SET render = '{subject} decided' WHERE name = 'decided' AND seed = 1;
UPDATE event_type SET render = '{subject} met {object}' WHERE name = 'met' AND seed = 1;
UPDATE event_type SET render = '{subject} bought {object}' WHERE name = 'purchased' AND seed = 1;
UPDATE event_type SET render = '{subject} sold {object}' WHERE name = 'sold' AND seed = 1;
UPDATE event_type SET render = '{subject} died' WHERE name = 'died' AND seed = 1;
UPDATE event_type SET render = '{subject} was dissolved' WHERE name = 'dissolved' AND seed = 1;
