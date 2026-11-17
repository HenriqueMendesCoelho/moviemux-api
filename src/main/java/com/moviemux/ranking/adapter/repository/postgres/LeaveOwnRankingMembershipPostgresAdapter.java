package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.port.LeaveOwnRankingMembership;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LeaveOwnRankingMembershipPostgresAdapter implements LeaveOwnRankingMembership {
  private final NamedParameterJdbcTemplate jdbc;
  public LeaveOwnRankingMembershipPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Outcome leave(UUID rankingId, UUID actorUserId) {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(actorUserId, "actorUserId");
    var params = Map.of("ranking", rankingId, "actor", actorUserId);
    // Hold this lock through commit: concurrent ownership updates cannot bypass the owner guard.
    var owners = jdbc.query("select owner_user_id from ranking where id=:ranking for update",
        params, (row, index) -> row.getObject("owner_user_id", UUID.class));
    if (owners.isEmpty()) return Outcome.NOT_FOUND;
    if (actorUserId.equals(owners.get(0))) return Outcome.OWNER_REQUIRES_DESTINATION;
    var members = jdbc.query("""
        select active from ranking_membership
        where ranking_id=:ranking and user_id=:actor for update
        """, params, (row, index) -> row.getBoolean("active"));
    if (members.isEmpty()) return Outcome.NOT_FOUND;
    if (members.get(0)) jdbc.update("""
        update ranking_membership set active=false
        where ranking_id=:ranking and user_id=:actor
        """, params);
    return Outcome.LEFT;
  }
}
