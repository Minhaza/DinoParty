-- Phase 4: durable record of game sessions, rounds and results (docs/game-engine.md section 8).
-- Live game state is never stored here; rows are written at lifecycle boundaries only.

CREATE TABLE game_session (
    id             uuid PRIMARY KEY,
    room_id        uuid        NOT NULL REFERENCES room (id) ON DELETE RESTRICT,
    game_key       varchar(40) NOT NULL REFERENCES game_definition (key),
    session_number integer     NOT NULL,
    status         varchar(12) NOT NULL,
    end_reason     varchar(32),
    settings       jsonb       NOT NULL,
    roster         jsonb       NOT NULL,
    seed           bigint      NOT NULL,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz,
    CONSTRAINT ck_game_session_status CHECK (status IN ('RUNNING', 'FINISHED', 'ABORTED', 'INTERRUPTED')),
    CONSTRAINT uq_game_session_room_number UNIQUE (room_id, session_number)
);

-- At most one running session per room, enforced by the database.
CREATE UNIQUE INDEX uq_game_session_running_room ON game_session (room_id) WHERE status = 'RUNNING';

CREATE TABLE game_round (
    id           uuid PRIMARY KEY,
    session_id   uuid        NOT NULL REFERENCES game_session (id) ON DELETE CASCADE,
    round_no     integer     NOT NULL,
    started_at   timestamptz NOT NULL,
    ended_at     timestamptz NOT NULL,
    end_reason   varchar(32) NOT NULL,
    score_deltas jsonb       NOT NULL DEFAULT '{}'::jsonb,
    meta         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_game_round_session_no UNIQUE (session_id, round_no)
);

-- No foreign key to guest_identity: purging guests must never destroy history (D21).
CREATE TABLE game_result (
    id         uuid PRIMARY KEY,
    session_id uuid         NOT NULL REFERENCES game_session (id) ON DELETE CASCADE,
    guest_id   uuid         NOT NULL,
    nickname   varchar(40)  NOT NULL,
    rank       integer      NOT NULL,
    score      integer      NOT NULL,
    detail     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_game_result_session_guest UNIQUE (session_id, guest_id)
);
