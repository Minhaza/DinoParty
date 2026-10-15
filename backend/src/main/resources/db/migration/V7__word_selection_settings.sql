-- WP-02: word selection settings of Draw & Guess (api-contracts section 2.2, game-rules [DG-15]).
-- New defaults: built-in words from every category (Random Mix). Existing rooms keep their stored
-- settings; a key they lack falls back to these defaults when a game starts.
UPDATE game_definition
SET default_settings = default_settings
    || '{"wordSource": "built-in", "wordCategories": ["random-mix"], "wordPackIds": []}'::jsonb
WHERE key = 'draw-guess';
