package com.moviemux.ranking.application.spring;

import com.moviemux.ranking.domain.port.CurrentRankingAccess;
import com.moviemux.ranking.domain.service.RankingAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RankingAccessConfiguration {
  @Bean
  RankingAuthorization rankingAuthorization(CurrentRankingAccess access) {
    return new RankingAuthorization(access);
  }
}
