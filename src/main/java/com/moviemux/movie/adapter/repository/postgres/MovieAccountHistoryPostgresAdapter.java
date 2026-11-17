package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.domain.repository.MovieAccountHistory;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MovieAccountHistoryPostgresAdapter implements MovieAccountHistory {
  private final NamedParameterJdbcTemplate jdbc;
  public MovieAccountHistoryPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
  @Override public boolean hasProtectedHistory(UUID userId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from movie_note where user_id=:user)",Map.of("user",Objects.requireNonNull(userId)),Boolean.class));
  }
}
