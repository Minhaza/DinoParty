package com.dinoparty.session;

import com.dinoparty.engine.GameRegistry;
import com.dinoparty.room.GameCatalog;
import com.dinoparty.room.GameInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Startup and shutdown behavior of the engine. Active games live only in memory (D13), so a start
 * after a crash first turns every RUNNING session into INTERRUPTED and returns stuck rooms to
 * WAITING, before the server accepts connections; a graceful stop aborts the games itself. Recovery
 * assumes a single instance: every RUNNING row is treated as orphaned.
 */
@Component
class SessionLifecycleCallbacks implements SmartInitializingSingleton, SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SessionLifecycleCallbacks.class);

  private final SessionRecovery recovery;
  private final SessionService sessions;
  private final GameRegistry registry;
  private final GameCatalog catalog;
  private final SessionProperties properties;
  private volatile boolean running;

  SessionLifecycleCallbacks(
      SessionRecovery recovery,
      SessionService sessions,
      GameRegistry registry,
      GameCatalog catalog,
      SessionProperties properties) {
    this.recovery = recovery;
    this.sessions = sessions;
    this.registry = registry;
    this.catalog = catalog;
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    Map<String, int[]> rows = new HashMap<>();
    for (GameInfo game : catalog.enabled()) {
      rows.put(game.key(), new int[] {game.minPlayers(), game.maxPlayers()});
    }
    List<String> problems = registry.verifyAgainstCatalog(rows, properties.strictRegistry());
    problems.forEach(p -> log.warn("Game registry: {}", p));
    if (properties.recoveryOnStartup()) {
      recovery.recover();
    }
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    sessions.shutdown();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Stops before the realtime layer closes its sockets, so clients hear GAME_ABORTED. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
