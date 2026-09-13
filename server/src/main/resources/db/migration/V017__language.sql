-- MNEMIC_LANGUAGE (2026-09-11): observations stay in the language they were said in; the fact layer is rendered in
-- the store's language from per-language templates. Seed templates are inserted by the registry at start for
-- every language it knows; a caller-defined predicate may carry 'renders' per language. store_meta records what
-- the facts were last rendered in, so a change of language re-renders them all. Never edit an applied migration.

CREATE TABLE predicate_render
(
    name            TEXT NOT NULL REFERENCES predicate (name) ON DELETE CASCADE,
    language        TEXT NOT NULL,
    render          TEXT NOT NULL,
    negated         TEXT,             -- the negation's template; null: the generic rule
    lexicon         TEXT NOT NULL,    -- JSON list: cue words in this language, merged into the predicate's
    inverse_lexicon TEXT NOT NULL,
    PRIMARY KEY (name, language)
);
CREATE TABLE store_meta
(
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
