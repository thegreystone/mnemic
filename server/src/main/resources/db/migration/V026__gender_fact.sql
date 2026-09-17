-- A person's gender is a fact under the seed predicate 'gender' (with provenance, correctable, read by the kinship
-- rules), not a column on the entity. The column V025 added goes; nothing released wrote to it.
ALTER TABLE entity DROP COLUMN gender;
