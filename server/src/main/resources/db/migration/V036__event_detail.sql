-- An event had nowhere to keep what its type and participants do not say. Retyping the sentence an early reading
-- put where the type goes ("took_2nd_place_in_the_kth_q_arne_val_melody_festival" to "competed") left the event
-- reading "Marcus Hirt competed (since 1997)" (2026-09-28). The detail is free text shown after the sentence, set
-- by correct(evt-N, {detail}) and carried over from a sentence type when the event is retyped. The engine fills it
-- in at start for events retyped before this, and re-renders every event after a migration.
-- Never edit an applied migration.
ALTER TABLE event ADD COLUMN detail TEXT;
