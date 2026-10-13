-- Test-only catalog row for the engine test game (src/test/java/com/dinoparty/games/testgame).
-- Lives outside db/migration so it can never reach a real database.
INSERT INTO game_definition (key, name, min_players, max_players, enabled, default_settings)
VALUES ('test-game', 'Test Game', 2, 6, true, '{}'::jsonb);
