package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.port.ReplaceRankingMemberCapabilities;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReplaceRankingMemberCapabilitiesPostgresAdapter implements ReplaceRankingMemberCapabilities {
  private final NamedParameterJdbcTemplate jdbc;
  public ReplaceRankingMemberCapabilitiesPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }
  private record LockedRanking(UUID ownerUserId, boolean archived) {}

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Outcome replace(UUID rankingId, UUID actorUserId, UUID memberUserId, Set<RankingCapability> capabilities) {
    var grants = Set.copyOf(capabilities);
    var params = Map.of("ranking", Objects.requireNonNull(rankingId), "actor", Objects.requireNonNull(actorUserId),
        "member", Objects.requireNonNull(memberUserId));
    // All ranking membership mutations lock the ranking first, including departure and ownership changes.
    var owners = jdbc.query("select owner_user_id, archived_at from ranking where id=:ranking for update",
        params, (row, index) -> new LockedRanking(row.getObject("owner_user_id", UUID.class), row.getObject("archived_at") != null));
    if (owners.isEmpty() || !actorUserId.equals(owners.get(0).ownerUserId())) return Outcome.NOT_FOUND;
    var ownerActive = jdbc.query("""
        select active from ranking_membership where ranking_id=:ranking and user_id=:actor for update
        """, params, (row, index) -> row.getBoolean("active"));
    if (ownerActive.isEmpty() || !ownerActive.get(0)) return Outcome.NOT_FOUND;
    if (owners.get(0).archived()) return Outcome.ARCHIVED_READ_ONLY;
    if (actorUserId.equals(memberUserId)) return Outcome.OWNER_CAPABILITIES_IMMUTABLE;
    var memberActive = jdbc.query("""
        select active from ranking_membership where ranking_id=:ranking and user_id=:member for update
        """, params, (row, index) -> row.getBoolean("active"));
    if (memberActive.isEmpty() || !memberActive.get(0)) return Outcome.NOT_FOUND;
    jdbc.update("delete from ranking_membership_grant where ranking_id=:ranking and user_id=:member", params);
    for (var capability : grants) jdbc.update("""
        insert into ranking_membership_grant(ranking_id,user_id,capability) values (:ranking,:member,:capability)
        """, Map.of("ranking", rankingId, "member", memberUserId, "capability", capability.name()));
    return Outcome.UPDATED;
  }
}
