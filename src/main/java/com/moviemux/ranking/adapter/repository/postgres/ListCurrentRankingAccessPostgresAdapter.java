package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ListCurrentRankingAccess;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListCurrentRankingAccessPostgresAdapter implements ListCurrentRankingAccess {
  private final NamedParameterJdbcTemplate jdbc;

  public ListCurrentRankingAccessPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override public List<RankingAccessSnapshot> findAccessible(UUID actorUserId, UUID after, int maxRows) {
    Objects.requireNonNull(actorUserId, "actorUserId");
    if (maxRows < 1 || maxRows > 101) throw new IllegalArgumentException("Invalid query bound");
    // Page ranking identities, not grant rows. Filtering and grant loading share one SQL snapshot.
    return jdbc.query("""
        with accessible as (
          select r.id, r.owner_user_id, r.visibility, r.admission, m.user_id, m.active
          from ranking r
          join ranking_membership m on m.ranking_id = r.id and m.user_id = :actor and m.active
          where (cast(:after as uuid) is null or r.id > cast(:after as uuid))
            and (r.owner_user_id = :actor or exists (
              select 1 from ranking_membership_grant allowed
              where allowed.ranking_id = r.id and allowed.user_id = :actor and allowed.capability = 'READ'))
          order by r.id limit :maxRows
        )
        select a.*, g.capability from accessible a
        left join ranking_membership_grant g on g.ranking_id = a.id and g.user_id = a.user_id
        order by a.id, g.capability
        """, new MapSqlParameterSource().addValue("actor", actorUserId)
        .addValue("after", after).addValue("maxRows", maxRows), rows -> {
      var result = new ArrayList<RankingAccessSnapshot>();
      boolean more = rows.next();
      while (more) {
        var ranking = new RankingIdentity(rows.getObject("id", UUID.class),
            rows.getObject("owner_user_id", UUID.class), RankingVisibility.valueOf(rows.getString("visibility")),
            RankingAdmission.valueOf(rows.getString("admission")));
        UUID member = rows.getObject("user_id", UUID.class);
        boolean active = rows.getBoolean("active");
        var grants = EnumSet.noneOf(RankingCapability.class);
        do {
          String grant = rows.getString("capability");
          if (grant != null) grants.add(RankingCapability.valueOf(grant));
          more = rows.next();
        } while (more && ranking.id().equals(rows.getObject("id", UUID.class)));
        result.add(new RankingAccessSnapshot(ranking,
            Optional.of(new RankingMembership(ranking.id(), member, active, grants))));
      }
      return List.copyOf(result);
    });
  }
}
