package com.dinoparty.common;

import org.springframework.stereotype.Component;

/**
 * Startup guard: a runtime without {@code jdk.random} once passed every health check and then
 * failed each game at the end of the start countdown (Render deploy, D49).
 */
@Component
class RandomRuntimeCheck {

  RandomRuntimeCheck() {
    SeededRandomSource.requireAvailable();
  }
}
