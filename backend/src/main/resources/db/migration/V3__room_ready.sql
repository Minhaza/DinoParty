-- Phase 3: server-authoritative ready flag, reset whenever a guest (re)joins.
ALTER TABLE room_participant ADD COLUMN ready boolean NOT NULL DEFAULT false;
