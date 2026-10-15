-- P6-02: Dino Telephone setting entriesPerChain (api-contracts section 2.2, game-rules [TP-2], D38).
-- 0 = automatic. Existing rooms keep their stored settings; a key they lack falls back to this
-- default when a game starts (as in V7).
UPDATE game_definition
SET default_settings = default_settings || '{"entriesPerChain": 0}'::jsonb
WHERE key = 'telephone';
