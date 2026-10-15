-- WP-01: built-in word catalog for Draw & Guess word packs (docs/draw-guess-design.md section 18).
-- Read-only reference data seeded by V6. Gameplay still draws from the versioned resource lists
-- (games/drawguess/words-*.txt); a test keeps both consistent until the game reads the catalog.

-- Stable, machine-readable category keys. "Random Mix" is a selection preset, never a row here.
CREATE TABLE word_category (
    key        varchar(40) PRIMARY KEY,
    sort_order integer     NOT NULL,
    active     boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_word_category_key CHECK (key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    CONSTRAINT uq_word_category_sort_order UNIQUE (sort_order)
);

-- User-facing labels per locale, so categories can be localized without new columns.
CREATE TABLE word_category_label (
    category_key varchar(40) NOT NULL REFERENCES word_category (key) ON DELETE CASCADE,
    locale       varchar(16) NOT NULL,
    label        varchar(60) NOT NULL,
    CONSTRAINT pk_word_category_label PRIMARY KEY (category_key, locale),
    CONSTRAINT ck_word_category_label_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Z]{2})?$'),
    CONSTRAINT ck_word_category_label_label CHECK (char_length(btrim(label)) > 0)
);

-- One row per built-in word and locale. MIXED is a filter, never a stored difficulty.
CREATE TABLE word_catalog_entry (
    id           uuid        PRIMARY KEY,
    locale       varchar(16) NOT NULL,
    display_text varchar(20) NOT NULL,
    category_key varchar(40) NOT NULL REFERENCES word_category (key),
    difficulty   varchar(8)  NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    CONSTRAINT ck_word_catalog_entry_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Z]{2})?$'),
    CONSTRAINT ck_word_catalog_entry_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    -- [DG-15] shape for every locale: 3-20 characters, lowercase, single inner spaces or hyphens.
    CONSTRAINT ck_word_catalog_entry_text CHECK (
        char_length(display_text) BETWEEN 3 AND 20
        AND display_text = lower(display_text)
        AND display_text !~ '(^[ -])|([ -]$)|([ -][ -])'
        AND display_text !~ '[[:cntrl:][:digit:]]'),
    -- English entries are plain ASCII letters, as the game's guess matcher expects.
    CONSTRAINT ck_word_catalog_entry_text_en CHECK (
        locale <> 'en' OR display_text ~ '^[a-z]+([ -][a-z]+)*$')
);

-- No duplicate within a locale, whatever the category or difficulty. Spaces and hyphens are
-- ignored, matching how guesses are compared ([DG-4]): "ice cream", "ice-cream" and "icecream"
-- are one entry.
CREATE UNIQUE INDEX uq_word_catalog_entry_locale_text
    ON word_catalog_entry (locale, (replace(replace(display_text, ' ', ''), '-', '')));

-- Filtering and counting by locale, category and difficulty.
CREATE INDEX ix_word_catalog_entry_filter
    ON word_catalog_entry (locale, category_key, difficulty) WHERE active;
