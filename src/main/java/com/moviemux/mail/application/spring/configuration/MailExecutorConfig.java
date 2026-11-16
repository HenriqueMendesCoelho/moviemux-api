package com.moviemux.mail.application.spring.configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MailExecutorConfig {
  @Bean(destroyMethod = "shutdown")
  public ThreadPoolExecutor mailExecutor() {
    // Keep this separate from Spring's TaskExecutor for the password-reset entry point.
    // Abort on saturation: never send mail on the transaction/HTTP caller's thread.
    return new ThreadPoolExecutor(
        1,
        2,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100),
        Thread.ofPlatform().name("mail-delivery-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
