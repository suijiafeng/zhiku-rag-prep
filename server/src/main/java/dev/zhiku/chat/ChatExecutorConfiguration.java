package dev.zhiku.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded pool for answer streams; saturation returns 503 instead of queueing unbounded model calls. */
@Configuration
class ChatExecutorConfiguration {
  @Bean
  TaskExecutor chatExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(16);
    executor.setThreadNamePrefix("chat-");
    executor.initialize();
    return executor;
  }
}
