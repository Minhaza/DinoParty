-- P9-02: catalog rows of the impostor games (api-contracts section 2.2, game-rules Games 3-4,
-- decisions D51, D62). Both stay disabled until their launch migration (P9-08). The modules own
-- behaviour and settings schemas; default_settings is the non-authoritative copy of their defaults
-- (guarded by ImpostorCatalogIT and the module defaults test).
INSERT INTO game_definition (key, name, min_players, max_players, enabled, default_settings) VALUES
    ('dino-impostor', 'Dino Impostor', 4, 10, false,
     '{"rounds": 0, "laps": 2, "turnSeconds": 25, "voteSeconds": 60, "guessSeconds": 30, "difficulty": "mixed"}'::jsonb),
    ('sketchy-saurus', 'Sketchy Saurus', 4, 10, false,
     '{"rounds": 0, "voteSeconds": 60, "guessSeconds": 30, "difficulty": "mixed", "sketchSeconds": 60, "impostorKnows": true, "unawareGuess": false}'::jsonb);
