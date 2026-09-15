-- Entity types become a registry like predicates and event types: seeded by the server, extensible by a caller,
-- with the synonyms that map a proposed type onto a registered one and the words that say what kind of thing an
-- entity is rather than which one. `parent` nests kinds (country within place). Corrections to either vocabulary
-- are logged in vocabulary_change, the way predicate_change logs predicate corrections.
CREATE TABLE entity_type
(
    name        TEXT PRIMARY KEY,
    description TEXT,
    parent      TEXT REFERENCES entity_type (name),
    synonyms    TEXT    NOT NULL DEFAULT '[]',
    type_words  TEXT    NOT NULL DEFAULT '[]',
    defined_by  INTEGER REFERENCES observation (id),
    seed        INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL
);

CREATE TABLE vocabulary_change
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    vocabulary TEXT NOT NULL, -- event_type | entity_type
    name       TEXT NOT NULL,
    field      TEXT NOT NULL,
    old_value  TEXT,
    new_value  TEXT,
    reason     TEXT,
    changed_at TEXT NOT NULL
);
