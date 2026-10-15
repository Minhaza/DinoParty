package com.dinoparty.games.drawguess;

import com.dinoparty.engine.GameSetup;
import com.dinoparty.words.WordPool;

/** What a Draw & Guess session freezes at start: its eligible word pool ([DG-15]). */
record DrawGuessSetup(WordPool pool) implements GameSetup {}
