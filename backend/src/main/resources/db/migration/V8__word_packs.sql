-- WP-03: room-scoped custom word packs (docs/api-contracts.md section 2.5, decision D34).
-- A pack belongs to one room and is deleted with it. word_count and content_version are kept in
-- step with the entries by the service while it holds the room's lock.

CREATE TABLE word_pack (
    id              uuid PRIMARY KEY,
    room_id         uuid         NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    name            varchar(80)  NOT NULL,
    description     varchar(280) NOT NULL DEFAULT '',
    word_count      integer      NOT NULL DEFAULT 0,
    content_version bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT ck_word_pack_name CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_word_pack_word_count CHECK (word_count BETWEEN 0 AND 1000)
);

CREATE INDEX ix_word_pack_room_id ON word_pack (room_id, created_at);

CREATE TABLE word_pack_entry (
    id         uuid PRIMARY KEY,
    pack_id    uuid        NOT NULL REFERENCES word_pack (id) ON DELETE CASCADE,
    position   integer     NOT NULL,
    text       varchar(80) NOT NULL,
    -- NFKC + lowercase form of text (accents kept): the case-insensitive duplicate key.
    -- (text, not varchar: NFKC can expand a character into several.)
    text_key   text        NOT NULL,
    category   varchar(40) NOT NULL DEFAULT 'Custom',
    difficulty varchar(8)  NOT NULL DEFAULT 'MEDIUM',
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_word_pack_entry_text CHECK (char_length(btrim(text)) > 0),
    CONSTRAINT ck_word_pack_entry_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    -- No duplicate in a pack, whatever the import path or race that produced it.
    CONSTRAINT uq_word_pack_entry_text_key UNIQUE (pack_id, text_key)
);

CREATE INDEX ix_word_pack_entry_order ON word_pack_entry (pack_id, position, id);
