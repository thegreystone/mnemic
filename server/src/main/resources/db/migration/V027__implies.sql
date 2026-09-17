-- What a predicate's qualifiers or values imply about the subject's attributes (parent_of: mother implies gender
-- female), declared on the predicate; the kinship rules choose a qualifier by any attribute predicate ('by'), not
-- by gender in particular. Seed implications are filled by the registry at start.
ALTER TABLE predicate ADD COLUMN implies TEXT;
