-- Phase 2: rooms, participants and the game catalog seed.

CREATE TABLE room (
    id            uuid PRIMARY KEY,
    code          varchar(6)  NOT NULL,
    game_key      varchar(40) NOT NULL REFERENCES game_definition (key),
    host_guest_id uuid        NOT NULL REFERENCES guest_identity (id),
    status        varchar(16) NOT NULL,
    max_players   integer     NOT NULL,
    settings      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL,
    closed_at     timestamptz,
    CONSTRAINT uq_room_code UNIQUE (code),
    CONSTRAINT ck_room_status CHECK (status IN ('WAITING', 'STARTING', 'PLAYING', 'RESULTS', 'CLOSED')),
    CONSTRAINT ck_room_max_players CHECK (max_players >= 1)
);

CREATE INDEX ix_room_host_guest_id ON room (host_guest_id);
CREATE INDEX ix_room_game_key ON room (game_key);

CREATE TABLE room_participant (
    id        uuid PRIMARY KEY,
    room_id   uuid        NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    guest_id  uuid        NOT NULL REFERENCES guest_identity (id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL,
    left_at   timestamptz,
    CONSTRAINT uq_room_participant_room_guest UNIQUE (room_id, guest_id)
);

CREATE INDEX ix_room_participant_guest_id ON room_participant (guest_id);

-- A guest can be an active member of only one room at a time. The database enforces it so that
-- concurrent create/join requests for different rooms cannot both succeed.
CREATE UNIQUE INDEX uq_room_participant_active_guest ON room_participant (guest_id) WHERE left_at IS NULL;

INSERT INTO game_definition (key, name, min_players, max_players, enabled, default_settings) VALUES
    ('draw-guess', 'Draw & Guess', 2, 10, true,
     '{"cycles": 2, "drawSeconds": 80, "wordChoiceSeconds": 15, "hintReveal": true, "difficulty": "mixed"}'::jsonb),
    ('telephone', 'Dino Telephone', 4, 8, true,
     '{"promptSeconds": 60, "drawSeconds": 90, "describeSeconds": 45, "allowSkipTimeout": true}'::jsonb);
