package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.RankingVisibilitySettings;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class RankingVisibilitySettingsPostgresAdapter implements RankingVisibilitySettings {
  private final NamedParameterJdbcTemplate jdbc;

  public RankingVisibilitySettingsPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public RankingVisibility current(UUID rankingId,UUID actorId) {
    return jdbc.queryForList("""
        select r.visibility from ranking r
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        where r.id=:rank and r.owner_user_id=:actor and r.archived_at is null
        """,Map.of("rank",rankingId,"actor",actorId),String.class).stream().findFirst()
        .map(RankingVisibility::valueOf).orElseThrow(RankingAccessDeniedException::new);
  }

  @Override public RankingVisibility set(UUID rankingId,UUID actorId,RankingVisibility visibility) {
    var scope=Map.of("rank",rankingId,"actor",actorId);
    // Lock rank, then current owner account. The predicate is rechecked after waiting for a rank change.
    var current=jdbc.queryForList("""
        select visibility from ranking where id=:rank and owner_user_id=:actor and archived_at is null for update
        """,scope,String.class).stream().findFirst().map(RankingVisibility::valueOf)
        .orElseThrow(RankingAccessDeniedException::new);
    if (jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        join ranking_membership m on m.ranking_id=:rank and m.user_id=u.id and m.active
        where u.id=:actor and s.consecutive_failed_login_attempts<10
        for key share of u for share of s
        """,scope,UUID.class).isEmpty()) throw new RankingAccessDeniedException();

    // No row write, timestamp or external effect on same-value retry. Admission is a separate decision.
    if (current!=visibility && jdbc.update("update ranking set visibility=:visibility where id=:rank",
        Map.of("rank",rankingId,"visibility",visibility.name()))!=1)
      throw new DataIntegrityViolationException("Collection visibility unavailable");
    return visibility;
  }
}
