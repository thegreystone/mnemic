-- M2: time. Bound provenance on facts, supersession records, the event-type registry, and the links a forgotten
-- observation leaves behind so history can show a tombstone (EVALUATION.md D2). Never edit an applied migration.

-- How each bound was obtained (DECISIONS.md §2.6): stated | resolved | assumed | event | correction
ALTER TABLE fact
    ADD COLUMN start_source TEXT;
ALTER TABLE fact
    ADD COLUMN end_source TEXT;
-- The fact that replaced this one, when status is superseded or corrected
ALTER TABLE fact
    ADD COLUMN superseded_by INTEGER REFERENCES fact (id);

-- Every change of status is recorded, never silent (EVALUATION.md metric: silent overwrite count = 0).
CREATE TABLE supersession
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    fact_id          INTEGER NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    superseded_by_id INTEGER REFERENCES fact (id) ON DELETE SET NULL,
    kind             TEXT    NOT NULL, -- event | correction | supersession | invalidation | entity_ended
    reason           TEXT,
    event_id         INTEGER REFERENCES event (id) ON DELETE SET NULL,
    observation_id   INTEGER REFERENCES observation (id) ON DELETE SET NULL,
    closed_at        TEXT,             -- the valid-time end written on the superseded fact, if any
    recorded_at      TEXT    NOT NULL
);
CREATE INDEX supersession_fact ON supersession (fact_id);
CREATE INDEX supersession_by ON supersession (superseded_by_id);

-- Which event types open and close which predicates (DECISIONS.md §2.10). Lists are JSON arrays.
CREATE TABLE event_type
(
    name        TEXT PRIMARY KEY,
    description TEXT,
    opens       TEXT    NOT NULL DEFAULT '[]', -- predicates a fact derived from this event may start
    closes      TEXT    NOT NULL DEFAULT '[]', -- predicates closed when subject and object both participate (left, divorced)
    supersedes  TEXT    NOT NULL DEFAULT '[]', -- functional predicates whose other current values close when a fact derives from this event (joined, moved)
    ends_entity INTEGER NOT NULL DEFAULT 0,    -- died, dissolved: closes the participant's open facts and existed interval
    defined_by  INTEGER REFERENCES observation (id),
    seed        INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL
);

-- Entities an observation's facts touched, kept when the observation is forgotten, so history shows the tombstone.
CREATE TABLE forgotten_link
(
    observation_id INTEGER NOT NULL REFERENCES observation (id) ON DELETE CASCADE,
    entity_id      INTEGER NOT NULL,
    PRIMARY KEY (observation_id, entity_id)
);
