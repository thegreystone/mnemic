-- An event cites every observation that stated it, as a fact does (fact_source): the one it was first read from
-- is its home, a later one that restated it or supplied its date is a source too. Forgetting one observation
-- leaves the event with the others, re-homed; forgetting the one that dated it takes the date back. Events from
-- before this cite their home; a rebuild replays the observations and finds the others.
CREATE TABLE event_source
(
    event_id       INTEGER NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    observation_id INTEGER NOT NULL REFERENCES observation (id) ON DELETE CASCADE,
    kind           TEXT    NOT NULL, -- stated | restated | dated
    PRIMARY KEY (event_id, observation_id)
);
INSERT INTO event_source(event_id, observation_id, kind) SELECT id, observation_id, 'stated' FROM event;
