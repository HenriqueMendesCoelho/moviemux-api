package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingCreationLimitException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.CreateRanking;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CreateRankingPostgresAdapter implements CreateRanking {
  private final NamedParameterJdbcTemplate jdbc;

  public CreateRankingPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RankingSummary create(UUID actor, String name, RankingVisibility visibility,
      RankingAdmission admission, RankingCreationQuota quota) {
    // Serialize same-owner creators before a fresh READ COMMITTED quota snapshot.
    // A hash collision only serializes unrelated creators; the count remains scoped to the UUID.
    var parameters = Map.of("actor", actor);
    jdbc.query("select pg_advisory_xact_lock(hashtextextended('moviemux.ranking.create:' || cast(:actor as text), 0))",
        parameters, rows -> { rows.next(); return null; });
    // KEY SHARE is compatible with other account reads and the offline migration's table SHARE lock.
    if (jdbc.queryForList("select id from \"user\" where id=:actor for key share", parameters, UUID.class).isEmpty())
      throw new RankingAccessDeniedException();

    // Archiving is terminal/read-only, not a way to free an owner's quota slot.
    Long owned = jdbc.queryForObject("select count(*) from ranking where owner_user_id=:actor",
        parameters, Long.class);
    if (owned >= quota.maxOwned()) throw new RankingCreationLimitException();

    UUID id = UUID.randomUUID();
    jdbc.update("""
        with created as (
          insert into ranking(id,owner_user_id,name,visibility,admission,created_at)
          values (:id,:actor,:name,:visibility,:admission,clock_timestamp())
          returning id,owner_user_id,created_at
        )
        insert into ranking_membership(ranking_id,user_id,active,created_at)
        select id,owner_user_id,true,created_at from created
        """, Map.of("id", id, "actor", actor, "name", name,
        "visibility", visibility.name(), "admission", admission.name()));
    return new RankingSummary(id, name, visibility, admission, EnumSet.allOf(RankingCapability.class));
  }
}
