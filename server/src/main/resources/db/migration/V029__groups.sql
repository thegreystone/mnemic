-- A predicate may belong to groups: a group is a word a question uses for several relations at once ("family" for
-- the kinship predicates), and a cue on it probes every member. Groups register from their first mention in a
-- predicate's definition, with the words of their name as cue words; a description and more words are corrections.
-- A group may itself belong to groups ("in_laws" within "family"): a question's group word reaches every predicate
-- under it, transitively. The seed puts the kinship predicates in "family" at start (Java), not here, so a fresh
-- store and an old one agree.
ALTER TABLE predicate ADD COLUMN groups TEXT NOT NULL DEFAULT '[]';
CREATE TABLE predicate_group
(
    name        TEXT PRIMARY KEY,
    description TEXT,
    lexicon     TEXT    NOT NULL DEFAULT '[]', -- JSON list: the cue words, the name's own words to begin with
    groups      TEXT    NOT NULL DEFAULT '[]', -- JSON list: the groups this group belongs to
    seed        INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL
);
CREATE TABLE predicate_group_render
(
    name     TEXT NOT NULL REFERENCES predicate_group (name) ON DELETE CASCADE,
    language TEXT NOT NULL,
    lexicon  TEXT NOT NULL DEFAULT '[]', -- JSON list: cue words in this language, merged into the group's
    PRIMARY KEY (name, language)
);
CREATE TABLE predicate_group_change
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_name  TEXT NOT NULL,
    field       TEXT NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    reason      TEXT,
    changed_at  TEXT NOT NULL
);
