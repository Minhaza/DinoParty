package com.dinoparty.session;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * One daemon timer thread. Its only job is to hand a {@link DeadlineWake} to a virtual thread; the
 * work happens inside the room mailbox (see {@link SessionWakeListener}), never on the timer.
 */
@Component
class ExecutorDeadlineScheduler implements DeadlineScheduler {

  private final ScheduledThreadPoolExecutor timer;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ApplicationEventPublisher events;
  private final Clock clock;

  ExecutorDeadlineScheduler(ApplicationEventPublisher events, Clock clock) {
    this.events = events;
    this.clock = clock;
    this.timer =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "engine-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    this.timer.setRemoveOnCancelPolicy(true);
  }

  @Override
  public Handle arm(String roomCode, UUID sessionInstance, Instant at) {
    long delayMs = Math.max(0, Duration.between(clock.instant(), at).toMillis());
    ScheduledFuture<?> future =
        timer.schedule(
            () ->
                workers.execute(
                    () -> events.publishEvent(new DeadlineWake(roomCode, sessionInstance))),
            delayMs,
            TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @PreDestroy
  void shutdown() {
    timer.shutdownNow();
    workers.close();
  }
}
