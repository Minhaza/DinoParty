package com.dinoparty.realtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Threads used by the realtime module, kept apart from the broker configuration it feeds. */
@Configuration(proxyBeanMethods = false)
class RealtimeInfrastructure {

  /** Heartbeats, delayed closes and the periodic maintenance sweep. */
  @Bean(name = "realtimeScheduler")
  ThreadPoolTaskScheduler realtimeScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("realtime-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  /** Runs room reconciliation off the socket threads (virtual threads: they may wait on locks). */
  @Bean(name = "presenceExecutor", destroyMethod = "close")
  ExecutorService presenceExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
