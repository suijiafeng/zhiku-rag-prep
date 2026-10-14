package dev.zhiku.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 回答流的有界线程池；饱和时返回 503，而不是让模型调用无限排队。 */
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
