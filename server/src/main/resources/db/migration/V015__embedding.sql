-- M4: a vector beside the row it describes, one per item and model, so two embedders can coexist and a change of
-- model is an incremental re-embedding. Vectors are little-endian float32; search is a scan (VectorStore) until a
-- store outgrows it and sqlite-vec takes over. Never edit an applied migration.

CREATE TABLE embedding
(
    item_kind  TEXT    NOT NULL, -- observation | fact
    item_id    INTEGER NOT NULL,
    model      TEXT    NOT NULL, -- the embedder's id: vectors from different models never mix
    dims       INTEGER NOT NULL,
    vec        BLOB    NOT NULL,
    created_at TEXT    NOT NULL,
    PRIMARY KEY (item_kind, item_id, model)
);
CREATE INDEX embedding_model ON embedding(model);
