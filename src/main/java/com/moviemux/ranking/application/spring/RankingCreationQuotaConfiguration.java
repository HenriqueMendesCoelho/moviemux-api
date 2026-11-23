package com.moviemux.ranking.application.spring;

import com.moviemux.ranking.domain.model.RankingCreationQuota;
import com.moviemux.ranking.domain.port.CurrentRankingCreationQuota;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RankingCreationQuotaConfiguration {
  @Bean
  CurrentRankingCreationQuota currentRankingCreationQuota(
      @Value("${ranking.creation.max-owned:}") String maxOwned,
      @Value("${ranking.creation.include-archived:}") String includeArchived) {
    var quota = parse(maxOwned, includeArchived);
    return () -> quota;
  }

  private static Optional<RankingCreationQuota> parse(String maxOwned, String includeArchived) {
    String rule = includeArchived.strip();
    if (!rule.equalsIgnoreCase("true") && !rule.equalsIgnoreCase("false")) return Optional.empty();
    try {
      int maximum = Integer.parseInt(maxOwned.strip());
      return maximum <= 0 ? Optional.empty()
          : Optional.of(new RankingCreationQuota(maximum, Boolean.parseBoolean(rule)));
    } catch (NumberFormatException invalid) {
      return Optional.empty();
    }
  }
}
