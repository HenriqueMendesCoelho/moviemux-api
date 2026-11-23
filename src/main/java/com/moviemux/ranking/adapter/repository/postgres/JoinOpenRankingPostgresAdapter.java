package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.port.JoinOpenRanking;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JoinOpenRankingPostgresAdapter implements JoinOpenRanking {
  private final NamedParameterJdbcTemplate jdbc;

  public JoinOpenRankingPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean join(UUID rankingId, UUID actorId) {
    var params = Map.of("rank", rankingId, "actor", actorId);
    // Serialize with all membership, ownership and admission changes on this ranking.
    if (jdbc.queryForList("""
        select id from ranking where id=:rank and admission='OPEN' and archived_at is null for update
        """, params, UUID.class).isEmpty()) return false;

    if (jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        where u.id=:actor and s.consecutive_failed_login_attempts<10
        for key share of u for share of s
        """, params, UUID.class).isEmpty()) return false;

    var memberships = jdbc.queryForList("""
        select active from ranking_membership where ranking_id=:rank and user_id=:actor for update
        """, params, Boolean.class);
    if (!memberships.isEmpty()) return memberships.getFirst();

    jdbc.update("""
        with created as (
          insert into ranking_membership(ranking_id,user_id,active,created_at)
          values (:rank,:actor,true,clock_timestamp()) returning ranking_id,user_id
        )
        insert into ranking_membership_grant(ranking_id,user_id,capability)
        select ranking_id,user_id,capability from created
        cross join (values ('READ'), ('RATE_OWN')) as grants(capability)
        """, params);
    return true;
  }
}
