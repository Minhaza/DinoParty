-- P9-08: launch both impostor games (docs/dino-impostor-plan.md section 9). Played by hand against
-- the real backend in P9-07; the rows from V11 only change availability, their defaults stay.
UPDATE game_definition SET enabled = true WHERE key IN ('dino-impostor', 'sketchy-saurus');
