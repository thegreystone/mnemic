-- M3: the persisted question queue (DECISIONS.md §2.7), entity merges with an undo trail, predicate changes.
-- A question returned only in a response is lost if the assistant does not surface it; here it stays open until
-- answered or dismissed, and the fact it holds is applied only then. Never edit an applied migration.

CREATE TABLE question
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    kind           TEXT    NOT NULL,                                -- entity_resolution | predicate_resolution | conflict | type_mismatch
    status         TEXT    NOT NULL DEFAULT 'open',                 -- open | answered | dismissed
    observation_id INTEGER REFERENCES observation (id) ON DELETE CASCADE,
    fact_id        INTEGER REFERENCES fact (id) ON DELETE SET NULL, -- the pending fact row, when one exists (conflict)
    subject        TEXT,
    predicate      TEXT,
    candidates     TEXT    NOT NULL DEFAULT '[]',                   -- JSON [{"n": 1, "id": "ent-4", "label": "...", "score": 0.5}]
    payload        TEXT,                                            -- JSON: the held proposal fragment, applied on resolution
    message        TEXT    NOT NULL,
    created_at     TEXT    NOT NULL,
    answered_at    TEXT,
    answer         TEXT
);
CREATE INDEX question_status ON question (status);
CREATE INDEX question_observation ON question (observation_id);

ALTER TABLE fact
    ADD COLUMN question_id INTEGER REFERENCES question (id) ON DELETE SET NULL;

-- Merges are recorded with what moved, so they can be reviewed and undone (COMPETITIVE.md: merge log with undo data).
CREATE TABLE entity_merge
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    from_id        INTEGER NOT NULL,
    into_id        INTEGER NOT NULL REFERENCES entity (id),
    from_name      TEXT    NOT NULL,
    moved_facts    TEXT    NOT NULL DEFAULT '[]', -- JSON fact ids re-pointed
    moved_aliases  TEXT    NOT NULL DEFAULT '[]',
    observation_id INTEGER REFERENCES observation (id) ON DELETE SET NULL,
    reason         TEXT,
    merged_at      TEXT    NOT NULL
);
CREATE INDEX entity_merge_into ON entity_merge (into_id);

-- A predicate can be corrected like a fact (EVALUATION.md J5); every change is logged.
CREATE TABLE predicate_change
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    predicate  TEXT NOT NULL,
    field      TEXT NOT NULL,
    old_value  TEXT,
    new_value  TEXT,
    reason     TEXT,
    changed_at TEXT NOT NULL
);
