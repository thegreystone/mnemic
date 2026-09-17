-- Derived predicates (EVALUATION.md family K): a predicate may carry rules that define it over other predicates, and
-- the facts those rules reach are materialised with the facts they rest on.
ALTER TABLE predicate ADD COLUMN rule TEXT; -- JSON list of rules, null for an asserted predicate

CREATE TABLE fact_derivation
(
    fact_id      INTEGER NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    rule         INTEGER NOT NULL,                                       -- index into the predicate's rules
    base_fact_id INTEGER NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL,                                       -- base | corroborates
    PRIMARY KEY (fact_id, base_fact_id)
);
CREATE INDEX fact_derivation_base ON fact_derivation (base_fact_id);
