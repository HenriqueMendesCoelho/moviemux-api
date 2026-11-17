package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAccountHistory;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RankingAccountHistoryPostgresAdapter implements RankingAccountHistory {
  private final NamedParameterJdbcTemplate jdbc;
  public RankingAccountHistoryPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
  @Override public boolean hasProtectedHistory(UUID userId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from ranking where owner_user_id=:user) or exists(select 1 from ranking_membership where user_id=:user)",Map.of("user",Objects.requireNonNull(userId)),Boolean.class));
  }
}
