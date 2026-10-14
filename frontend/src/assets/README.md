# DinoParty illustration assets

## Active artwork

| File                          | Used by                             | Purpose                                                       |
| ----------------------------- | ----------------------------------- | ------------------------------------------------------------- |
| `characters/dinosaurs.png`    | `DinoArtwork`, `DinoLogo`           | Shared transparent artwork for six avatars and hero dinosaurs |
| `backgrounds/hero-canyon.png` | `HeroScene`                         | Painted canyon, lake and grassy foreground                    |
| `backgrounds/page-soft.svg`   | `PageBackdrop` through `assetUrl()` | Quiet background for app pages                                |
| `games/draw-guess.png`        | `GameBanner` through `assetUrl()`   | Draw & Guess banner                                           |
| `games/telephone.png`         | `GameBanner` through `assetUrl()`   | Telephone banner                                              |

All artwork is original. Keep character designs consistent across portraits and scenes.
The dinosaur atlas has Rex, Tricera and Ptera in the top row; Stego, DinoMite and LavaLizard
in the bottom row. `DinoArtwork.tsx` defines explicit clipped regions to keep wide tails and
wings intact without exposing adjacent characters. Update those regions when replacing the atlas.
Preserve actual alpha transparency; never export browser screenshots as artwork.

The hero landscape follows the supplied canyon composition reference. Its grassy bank stays
at a stable vertical position on desktop. Rex and Tricera stand on the bank with contact
shadows and no bobbing animation; only Ptera floats. Compact layouts crop the landscape and
hide ground characters to keep controls clear. Legacy avatar exports, separate character
cutouts, geometric hero layers and unused foliage have been removed.

## Verification

The scene was visually checked at 1880px, 1280px and 390px widths. Shared artwork regression
tests cover all six avatar mappings and unique clipping IDs. Run the frontend formatting,
lint, TypeScript, test and build scripts before committing asset or component changes.

The header logo crops Rex from the shared atlas. The game banners are original painted
scenes using the current dinosaur designs: Rex painting with Tricera for Draw & Guess,
and dinosaurs passing drawings for Telephone.
