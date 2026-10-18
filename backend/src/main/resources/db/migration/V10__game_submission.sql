-- P6-03: every accepted or server-filled Dino Telephone entry, written when it is accepted and never
-- dropped (docs/telephone-design.md section 12.2, D44). Albums are rebuilt from
-- (session_id, chain_index, step_no); the author's nickname comes from game_session.roster.
-- No foreign key to guest_identity: purging guests must never destroy history (D21).
CREATE TABLE game_submission (
    id                uuid PRIMARY KEY,
    session_id        uuid        NOT NULL REFERENCES game_session (id) ON DELETE CASCADE,
    chain_index       integer     NOT NULL,
    step_no           integer     NOT NULL,
    author_guest_id   uuid        NOT NULL,
    kind              varchar(8)  NOT NULL,
    text              varchar(560),
    payload           jsonb,
    fill              varchar(12) NOT NULL,
    placeholder       boolean     NOT NULL,
    timed_out         boolean     NOT NULL,
    entries_per_chain smallint    NOT NULL,
    submitted_at      timestamptz NOT NULL,
    CONSTRAINT ck_game_submission_kind CHECK (kind IN ('TEXT', 'DRAWING')),
    CONSTRAINT ck_game_submission_fill CHECK (fill IN ('NONE', 'TIMEOUT', 'LEFT_PLAYER', 'AUTO_PROMPT')),
    CONSTRAINT ck_game_submission_content CHECK (
        (kind = 'TEXT' AND text IS NOT NULL AND payload IS NULL)
        OR (kind = 'DRAWING' AND text IS NULL AND payload IS NOT NULL)),
    CONSTRAINT ck_game_submission_position CHECK (
        chain_index >= 0 AND step_no >= 0 AND step_no < entries_per_chain),
    CONSTRAINT uq_game_submission_cell UNIQUE (session_id, chain_index, step_no)
);
