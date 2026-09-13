-- M1: facts as keys (DECISIONS.md §2.1). Entities, the predicate registry, events, facts with span offsets back to
-- their observation, and an FTS5 index over fact renderings. Temporal columns are present from the start so M2
-- adds semantics, not schema. Never edit an applied migration.

CREATE TABLE entity
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    type          TEXT NOT NULL,                       -- person, organization, place, project, ... (lowercase)
    created_from  INTEGER REFERENCES observation (id), -- NULL for the owner
    merged_into   INTEGER REFERENCES entity (id),      -- set by consolidate merges (M3)
    existed_start TEXT,
    existed_end   TEXT,
    created_at    TEXT NOT NULL
);

CREATE TABLE entity_alias
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_id          INTEGER NOT NULL REFERENCES entity (id) ON DELETE CASCADE,
    alias              TEXT    NOT NULL,
    alias_norm         TEXT    NOT NULL, -- lowercased, diacritics folded, whitespace collapsed
    source_observation INTEGER REFERENCES observation (id),
    UNIQUE (entity_id, alias_norm)
);
CREATE INDEX entity_alias_norm ON entity_alias (alias_norm);

-- Trigram index over aliases for fuzzy resolution and query-time spotting (DECISIONS.md §4: trigram on aliases only).
CREATE
VIRTUAL TABLE entity_alias_fts USING fts5(
    alias_norm,
    content='entity_alias',
    content_rowid='id',
    tokenize='trigram'
);
CREATE TRIGGER entity_alias_ai
    AFTER INSERT
    ON entity_alias
BEGIN
    INSERT INTO entity_alias_fts(rowid, alias_norm) VALUES (new.id, new.alias_norm);
END;
CREATE TRIGGER entity_alias_ad
    AFTER DELETE
    ON entity_alias
BEGIN
    INSERT INTO entity_alias_fts(entity_alias_fts, rowid, alias_norm) VALUES ('delete', old.id, old.alias_norm);
END;

-- The predicate registry (EXTRACTION.md, Predicate registry). Lists are JSON arrays.
CREATE TABLE predicate
(
    name             TEXT PRIMARY KEY,
    description      TEXT,
    domain           TEXT    NOT NULL,                  -- JSON array of entity types, or ["*"]
    range            TEXT    NOT NULL,                  -- JSON array of entity types, ["literal"], or ["*"]
    functional       INTEGER NOT NULL DEFAULT 0,
    functional_scope TEXT,                              -- 'scope' = functional per (subject, scope entity)
    symmetric        INTEGER NOT NULL DEFAULT 0,
    inverse          TEXT,
    volatility       TEXT    NOT NULL DEFAULT 'medium', -- low | medium | high
    lexicon          TEXT    NOT NULL DEFAULT '[]',
    render           TEXT    NOT NULL,
    qualifiers       TEXT    NOT NULL DEFAULT '[]',
    aliases          TEXT    NOT NULL DEFAULT '[]',
    defined_by       INTEGER REFERENCES observation (id),
    seed             INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT    NOT NULL
);

CREATE TABLE event
(
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    type                  TEXT    NOT NULL,
    observation_id        INTEGER NOT NULL REFERENCES observation (id) ON DELETE CASCADE,
    valid_start           TEXT,
    valid_start_precision TEXT,
    valid_end             TEXT,
    valid_end_precision   TEXT,
    rendering             TEXT    NOT NULL,
    created_at            TEXT    NOT NULL
);
CREATE INDEX event_observation ON event (observation_id);

CREATE TABLE event_participant
(
    event_id  INTEGER NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    entity_id INTEGER NOT NULL REFERENCES entity (id),
    role      TEXT,
    PRIMARY KEY (event_id, entity_id)
);
CREATE INDEX event_participant_entity ON event_participant (entity_id);

CREATE TABLE fact
(
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_id            INTEGER NOT NULL REFERENCES entity (id),
    predicate             TEXT    NOT NULL REFERENCES predicate (name),
    object_id             INTEGER REFERENCES entity (id),     -- entity object ...
    object_text           TEXT,                               -- ... or literal object (roles, values)
    qualifier             TEXT,
    scope_id              INTEGER REFERENCES entity (id),     -- e.g. the organization a role is held at
    valid_start           TEXT,
    valid_start_precision TEXT,
    valid_end             TEXT,
    valid_end_precision   TEXT,
    ended                 INTEGER NOT NULL DEFAULT 0,
    status                TEXT    NOT NULL DEFAULT 'current', -- current | superseded | corrected | pending | invalidated
    derivation_kind       TEXT    NOT NULL,                   -- explicit | extracted | inferred | derived
    observation_id        INTEGER NOT NULL REFERENCES observation (id) ON DELETE CASCADE,
    event_id              INTEGER REFERENCES event (id),
    span_start            INTEGER,                            -- offsets into observation.text, when locatable
    span_end              INTEGER,
    rendering             TEXT    NOT NULL,
    spec_version          INTEGER,
    caller_confidence     REAL,                               -- evidence about the caller, never the fact's confidence
    corroborations        INTEGER NOT NULL DEFAULT 1,
    last_confirmed        TEXT    NOT NULL,
    created_at            TEXT    NOT NULL
);
CREATE INDEX fact_subject_predicate ON fact (subject_id, predicate, status);
CREATE INDEX fact_object_predicate ON fact (object_id, predicate, status);
CREATE INDEX fact_observation ON fact (observation_id);

-- Renderings are the keys the lexical channel indexes (facts as keys, observations as values).
CREATE
VIRTUAL TABLE fact_fts USING fts5(
    rendering,
    content='fact',
    content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);
INSERT INTO fact_fts(fact_fts, rank)
VALUES ('secure-delete', 1);
CREATE TRIGGER fact_ai
    AFTER INSERT
    ON fact
BEGIN
    INSERT INTO fact_fts(rowid, rendering) VALUES (new.id, new.rendering);
END;
CREATE TRIGGER fact_ad
    AFTER DELETE
    ON fact
BEGIN
    INSERT INTO fact_fts(fact_fts, rowid, rendering) VALUES ('delete', old.id, old.rendering);
END;
CREATE TRIGGER fact_au
    AFTER UPDATE OF rendering
    ON fact
BEGIN
    INSERT INTO fact_fts(fact_fts, rowid, rendering) VALUES ('delete', old.id, old.rendering);
    INSERT INTO fact_fts(rowid, rendering) VALUES (new.id, new.rendering);
END;
