package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.CurrentRankingAccess;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** One statement observes ranking, current membership and grants consistently; no JWT/cache grants. */
@Repository
public class CurrentRankingAccessPostgresAdapter implements CurrentRankingAccess {
  private final NamedParameterJdbcTemplate jdbc;

  public CurrentRankingAccessPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<RankingAccessSnapshot> find(UUID rankingId, UUID actorUserId) {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(actorUserId, "actorUserId");
    return jdbc.query("""
        select r.id, r.owner_user_id, r.visibility, r.admission, r.archived_at,
               m.user_id as member_user_id, m.active, g.capability
        from ranking r
        left join ranking_membership m on m.ranking_id = r.id and m.user_id = :actor
        left join ranking_membership_grant g on g.ranking_id = m.ranking_id and g.user_id = m.user_id
        where r.id = :ranking
        order by g.capability
        """, Map.of("ranking", rankingId, "actor", actorUserId), rows -> {
      if (!rows.next()) return Optional.empty();
      var ranking = new RankingIdentity(rows.getObject("id", UUID.class),
          rows.getObject("owner_user_id", UUID.class),
          RankingVisibility.valueOf(rows.getString("visibility")),
          RankingAdmission.valueOf(rows.getString("admission")), rows.getObject("archived_at") != null);
      UUID memberId = rows.getObject("member_user_id", UUID.class);
      if (memberId == null) return Optional.of(new RankingAccessSnapshot(ranking, Optional.empty()));
      boolean active = rows.getBoolean("active");
      var grants = EnumSet.noneOf(RankingCapability.class);
      do {
        String capability = rows.getString("capability");
        if (capability != null) grants.add(RankingCapability.valueOf(capability));
      } while (rows.next());
      return Optional.of(new RankingAccessSnapshot(ranking,
          Optional.of(new RankingMembership(ranking.id(), memberId, active, grants))));
    });
  }
}
