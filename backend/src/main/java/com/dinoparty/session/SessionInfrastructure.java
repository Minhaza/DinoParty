package com.dinoparty.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class SessionInfrastructure {

  @Bean(name = "sessionScheduler")
  ThreadPoolTaskScheduler sessionScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("engine-watchdog-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
