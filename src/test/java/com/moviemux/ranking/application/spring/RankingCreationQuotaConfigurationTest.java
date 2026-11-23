package com.moviemux.ranking.application.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.moviemux.ranking.domain.model.RankingCreationQuota;
import org.junit.jupiter.api.Test;

class RankingCreationQuotaConfigurationTest {
  private final RankingCreationQuotaConfiguration configuration = new RankingCreationQuotaConfiguration();

  @Test void requiresExplicitPositiveLimitAndArchiveCountingRule() {
    for (String max : new String[]{"", "invalid", "0", "-2", "2147483648"}) {
      assertThat(configuration.currentRankingCreationQuota(max, "true").find()).isEmpty();
    }
    for (String rule : new String[]{"", "unknown", "0", "1"}) {
      assertThat(configuration.currentRankingCreationQuota("2", rule).find()).isEmpty();
    }
    assertThat(configuration.currentRankingCreationQuota("3", "true").find())
        .contains(new RankingCreationQuota(3, true));
    assertThat(configuration.currentRankingCreationQuota("3", "false").find())
        .contains(new RankingCreationQuota(3, false));
  }
}
