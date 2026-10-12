-- DinoParty baseline schema (Phase 1). Remaining tables land in the phase that needs them (see docs/architecture.md §3.6).

CREATE TABLE guest_identity (
    id           uuid PRIMARY KEY,
    nickname     varchar(20)  NOT NULL,
    avatar_key   varchar(40)  NOT NULL,
    avatar_color varchar(16),
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL
);

CREATE TABLE guest_session (
    id           uuid PRIMARY KEY,
    guest_id     uuid         NOT NULL REFERENCES guest_identity (id) ON DELETE CASCADE,
    token_hash   varchar(64)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    last_seen_at timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    CONSTRAINT uq_guest_session_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_guest_session_guest_id ON guest_session (guest_id);

CREATE TABLE game_definition (
    key              varchar(40) PRIMARY KEY,
    name             varchar(80) NOT NULL,
    min_players      smallint    NOT NULL,
    max_players      smallint    NOT NULL,
    enabled          boolean     NOT NULL DEFAULT true,
    default_settings jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_game_definition_players CHECK (min_players >= 1 AND max_players >= min_players)
);
