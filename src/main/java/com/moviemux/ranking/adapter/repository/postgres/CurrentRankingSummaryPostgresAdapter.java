package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.RankingAdmission;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RankingContextSummary;
import com.moviemux.ranking.domain.model.RankingSummary;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.CurrentRankingSummary;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CurrentRankingSummaryPostgresAdapter implements CurrentRankingSummary {
  private final NamedParameterJdbcTemplate jdbc;

  public CurrentRankingSummaryPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RankingContextSummary> find(UUID actor, UUID rankingId) {
    return jdbc.query("""
        select r.id,r.name,r.visibility,r.admission,r.archived_at is not null as archived,
          r.owner_user_id=:actor as owned,g.capability
        from ranking r
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        left join ranking_membership_grant g on g.ranking_id=r.id and g.user_id=m.user_id
        where r.id=:rankingId
          and (r.owner_user_id=:actor or exists (
            select 1 from ranking_membership_grant permitted
            where permitted.ranking_id=r.id and permitted.user_id=m.user_id and permitted.capability='READ'))
        """, Map.of("actor", actor, "rankingId", rankingId), rows -> {
      if (!rows.next()) return Optional.empty();
      UUID id = rows.getObject("id", UUID.class);
      String name = rows.getString("name");
      var visibility = RankingVisibility.valueOf(rows.getString("visibility"));
      var admission = RankingAdmission.valueOf(rows.getString("admission"));
      boolean archived=rows.getBoolean("archived");
      boolean owned=rows.getBoolean("owned");
      final EnumSet<RankingCapability> capabilities;
      if (archived) {
        capabilities = EnumSet.of(RankingCapability.READ);
      } else if (owned) {
        capabilities = EnumSet.allOf(RankingCapability.class);
      } else {
        capabilities = EnumSet.noneOf(RankingCapability.class);
        do {
          String grant = rows.getString("capability");
          if (grant != null) capabilities.add(RankingCapability.valueOf(grant));
        } while (rows.next());
      }
      // Never treat complete grants as ownership. The query requires live account and membership state.
      var summary=new RankingSummary(id,name,visibility,admission,capabilities);
      return Optional.of(new RankingContextSummary(summary,owned && !archived && admission==RankingAdmission.OPEN));
    });
  }
}
