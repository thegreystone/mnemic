-- M3 pilot finding: events are keys too. "What play did I attend?" was answered by an attended(user, The Glass
-- Menagerie) event with no fact beside it, and the recall key channel searched facts only. Event renderings
-- get the same FTS5 index facts have (V002), pointing at the observation the event came from.
-- Never edit an applied migration.

CREATE
VIRTUAL TABLE event_fts USING fts5(
    rendering,
    content='event',
    content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);
INSERT INTO event_fts(event_fts, rank)
VALUES ('secure-delete', 1);
CREATE TRIGGER event_ai
    AFTER INSERT
    ON event
BEGIN
    INSERT INTO event_fts(rowid, rendering) VALUES (new.id, new.rendering);
END;
CREATE TRIGGER event_ad
    AFTER DELETE
    ON event
BEGIN
    INSERT INTO event_fts(event_fts, rowid, rendering) VALUES ('delete', old.id, old.rendering);
END;
CREATE TRIGGER event_au
    AFTER UPDATE OF rendering
    ON event
BEGIN
    INSERT INTO event_fts(event_fts, rowid, rendering) VALUES ('delete', old.id, old.rendering);
    INSERT INTO event_fts(rowid, rendering) VALUES (new.id, new.rendering);
END;
INSERT INTO event_fts(rowid, rendering)
SELECT id, rendering
FROM event;
