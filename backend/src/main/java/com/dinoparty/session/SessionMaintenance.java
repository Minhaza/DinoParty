package com.dinoparty.session;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** Periodically runs the deadline watchdog (a safety net; deadlines normally fire from timers). */
@Component
class SessionMaintenance {

  private final SessionService sessions;
  private final ThreadPoolTaskScheduler scheduler;
  private ScheduledFuture<?> task;

  SessionMaintenance(
      SessionService sessions, @Qualifier("sessionScheduler") ThreadPoolTaskScheduler scheduler) {
    this.sessions = sessions;
    this.scheduler = scheduler;
  }

  @PostConstruct
  void start() {
    task = scheduler.scheduleWithFixedDelay(sessions::watchdog, Duration.ofSeconds(5));
  }

  @PreDestroy
  void stop() {
    if (task != null) {
      task.cancel(false);
    }
  }
}
