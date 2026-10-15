package com.dinoparty.games.telephone;

import com.dinoparty.engine.GameSetup;
import java.util.List;

/** What a Telephone session freezes at start: the auto-prompt pool ([TP-3], D46). */
record TelephoneSetup(List<String> prompts) implements GameSetup {

  TelephoneSetup {
    prompts = List.copyOf(prompts);
  }
}
