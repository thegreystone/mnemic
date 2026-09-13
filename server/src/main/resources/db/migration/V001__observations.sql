-- M0 baseline: observations are the canonical input (DESIGN.md, Knowledge model). Everything else is derived.
-- Facts, entities, events, predicates arrive in V002+ (PLAN.md M1-M3). Never edit an applied migration.

CREATE TABLE observation
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    text            TEXT NOT NULL, -- verbatim, never split by Mnemic
    source_kind     TEXT NOT NULL, -- user | assistant | document | connector
    source_ref      TEXT,          -- document path, message id, session id, ...
    source_chunk    INTEGER,       -- chunk index within source_ref, when chunked by the caller
    assistant       TEXT,          -- client name/version from clientInfo, when known
    session         TEXT,          -- caller-supplied session id, when known
    observed_at     TEXT NOT NULL, -- when it was said (may be a past conversation date)
    recorded_at     TEXT NOT NULL, -- when Mnemic stored it (transaction time)
    proposal_json   TEXT,          -- the caller's structured proposal, verbatim, for re-derivation
    spec_version    INTEGER,       -- extraction spec version the proposal was built under
    content_hash    TEXT NOT NULL, -- sha-256 of text, for duplicate detection
    idempotency_key TEXT UNIQUE,   -- caller-supplied; a repeat with the same key returns the same id
    forgotten_at    TEXT           -- tombstone; text is blanked when set (EVALUATION.md D2)
);

CREATE INDEX observation_observed_at ON observation (observed_at);
CREATE INDEX observation_source ON observation (source_ref, source_chunk);
CREATE INDEX observation_content_hash ON observation (content_hash);

-- Lexical channel over observation text. External-content: the index stores no text and can be rebuilt
-- from the observation table (EVALUATION.md H2). Diacritics folded so "Schübelbach" matches "Schubelbach".
CREATE
VIRTUAL TABLE observation_fts USING fts5(
    text,
    content='observation',
    content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);

-- FTS5's own secure-delete (SQLite >= 3.42): deleted terms are removed from the index immediately, not at merge.
INSERT INTO observation_fts(observation_fts, rank)
VALUES ('secure-delete', 1);

CREATE TRIGGER observation_ai
    AFTER INSERT
    ON observation
BEGIN
    INSERT INTO observation_fts(rowid, text) VALUES (new.id, new.text);
END;

CREATE TRIGGER observation_ad
    AFTER DELETE
    ON observation
BEGIN
    INSERT INTO observation_fts(observation_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;

CREATE TRIGGER observation_au
    AFTER UPDATE OF text
    ON observation
BEGIN
    INSERT INTO observation_fts(observation_fts, rowid, text) VALUES ('delete', old.id, old.text);
    INSERT INTO observation_fts(rowid, text) VALUES (new.id, new.text);
END;
