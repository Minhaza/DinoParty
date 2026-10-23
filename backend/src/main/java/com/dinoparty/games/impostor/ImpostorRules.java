package com.dinoparty.games.impostor;

import java.util.function.BooleanSupplier;

/**
 * What differs between the impostor games in the shared voting flow: the name of the voting phase
 * ({@code VOTE}, Sketchy Saurus {@code GALLERY}), the times, and whether a caught impostor gets a
 * guess ([DI-11]; Sketchy Saurus decides by {@code impostorKnows}/{@code unawareGuess}, [SS-3]).
 */
record ImpostorRules(
    String votePhase, int voteSeconds, int guessSeconds, BooleanSupplier impostorMayGuess) {}
