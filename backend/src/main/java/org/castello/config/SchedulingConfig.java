package org.castello.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfig {

    @Bean(name = "raidTaskScheduler")
    public ThreadPoolTaskScheduler raidTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("raid-scheduler-");
        s.initialize();
        return s;
    }

    /**
     * Scheduler dédié au tick des bots (voir bot/BotOrchestrator) : un pool
     * séparé pour que les décisions des bots ne retardent jamais les timers
     * de phase du raidTaskScheduler.
     */
    @Bean(name = "botTaskScheduler")
    public ThreadPoolTaskScheduler botTaskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("bot-scheduler-");
        s.initialize();
        return s;
    }
}