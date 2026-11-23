package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ListRankingSummaries;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListRankingSummariesPostgresAdapter implements ListRankingSummaries {
  private final NamedParameterJdbcTemplate jdbc;

  public ListRankingSummariesPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override public List<RankingSummary> find(UUID actor, UUID after, int maxRows) {
    Objects.requireNonNull(actor);
    if (maxRows < 1 || maxRows > 101) throw new IllegalArgumentException("Invalid query bound");
    return jdbc.query("""
        with accessible as (
          select r.id,r.name,r.visibility,r.admission,r.archived_at is not null as archived,
            r.owner_user_id=:actor as owned
          from ranking r
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          where (cast(:after as uuid) is null or r.id>cast(:after as uuid))
            and (r.owner_user_id=:actor or exists (
               select 1 from ranking_membership_grant permitted
               where permitted.ranking_id=r.id and permitted.user_id=:actor and permitted.capability='READ'))
          order by r.id limit :maxRows
        )
        select a.*, g.capability from accessible a
        left join ranking_membership_grant g on g.ranking_id=a.id and g.user_id=:actor
        order by a.id, g.capability
        """, new MapSqlParameterSource().addValue("actor", actor).addValue("after", after)
        .addValue("maxRows", maxRows), rows -> {
      var result = new ArrayList<RankingSummary>();
      boolean more = rows.next();
      while (more) {
        UUID id = rows.getObject("id", UUID.class);
        String name = rows.getString("name");
        var visibility = RankingVisibility.valueOf(rows.getString("visibility"));
        var admission = RankingAdmission.valueOf(rows.getString("admission"));
        boolean archived = rows.getBoolean("archived");
        boolean owned = rows.getBoolean("owned");
        var grants = EnumSet.noneOf(RankingCapability.class);
        do {
          String grant = rows.getString("capability");
          if (grant != null) grants.add(RankingCapability.valueOf(grant));
          more = rows.next();
        } while (more && id.equals(rows.getObject("id", UUID.class)));
        result.add(new RankingSummary(id, name, visibility, admission,
            archived ? EnumSet.of(RankingCapability.READ)
                : owned ? EnumSet.allOf(RankingCapability.class) : grants));
      }
      return List.copyOf(result);
    });
  }
}
