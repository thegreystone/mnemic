-- An entity type has words that name a kind of it ("hotel" of place, "clinic" of organization, "dog" of animal),
-- apart from its type words, which are affixes of the same thing. A type proposed under such a word is placed under
-- the type from its first use instead of being asked about (ten of a usage run's questions were "is a hotel a kind
-- of place?", 2026-09-23). NULL means the row predates the column: the seed's words are written once at the next
-- start, and from then on the list is the store's own, grown by definitions and corrections.
ALTER TABLE entity_type ADD COLUMN kinds TEXT;
