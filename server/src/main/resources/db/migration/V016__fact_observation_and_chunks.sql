-- Two things the semantic ablation exposed (2026-09-11, BENCHMARKS.md). A fact restated in several observations was
-- one row that belonged to the first of them: every observation that stated or corroborated a fact is now on record,
-- so recall can reach any of them, forgetting one re-homes the fact to the next, and a user can ask which
-- conversations said a thing. And a long observation was one vector cut at 512 tokens: vectors are now per chunk.
-- The embedding table is recreated (it was new and opt-in); consolidate re-embeds. Never edit an applied migration.

CREATE TABLE fact_source
(
    fact_id        INTEGER NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    observation_id INTEGER NOT NULL REFERENCES observation (id),
    kind           TEXT    NOT NULL, -- stated | corroborated
    recorded_at    TEXT    NOT NULL,
    PRIMARY KEY (fact_id, observation_id)
);
CREATE INDEX fact_source_obs ON fact_source(observation_id);
INSERT INTO fact_source(fact_id, observation_id, kind, recorded_at) SELECT id, observation_id, 'stated', created_at FROM fact;

DROP TABLE embedding;
CREATE TABLE embedding
(
    item_kind  TEXT    NOT NULL,
    item_id    INTEGER NOT NULL,
    model      TEXT    NOT NULL,
    chunk      INTEGER NOT NULL DEFAULT 0,
    dims       INTEGER NOT NULL,
    vec        BLOB    NOT NULL,
    created_at TEXT    NOT NULL,
    PRIMARY KEY (item_kind, item_id, model, chunk)
);
CREATE INDEX embedding_model ON embedding(model);
