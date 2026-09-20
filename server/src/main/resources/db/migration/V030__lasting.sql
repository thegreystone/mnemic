-- A lasting relation is one a participant's death does not end: a father stays a father, a sibling a sibling,
-- while a marriage or a job ends with the person. It is a property of the predicate, told apart from volatility
-- (a marriage never goes stale, and still ends with the spouse). The seed kinship predicates are marked at start
-- (Java), so a fresh store and an old one agree; a store's own predicates say it in their definition.
ALTER TABLE predicate ADD COLUMN lasting INTEGER NOT NULL DEFAULT 0;
