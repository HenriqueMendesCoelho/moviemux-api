package com.moviemux.user.application.spring;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserTimeConfiguration {
  @Bean
  public Clock userClock() {
    return Clock.systemDefaultZone();
  }
}
