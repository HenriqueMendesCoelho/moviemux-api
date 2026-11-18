package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.CurrentEntryRatingAggregate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** One authorized snapshot; hidden rating rows are not inputs to the aggregate. */
@Repository
public class CurrentEntryRatingAggregatePostgresAdapter implements CurrentEntryRatingAggregate {
  private final NamedParameterJdbcTemplate jdbc;
  public CurrentEntryRatingAggregatePostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public Optional<EntryRatingAggregateView> find(UUID rankingId,UUID entryId,UUID actorUserId) {
    var rows=jdbc.query("""
        with eligible as (
          select e.ranking_id,e.entry_id from ranking_movie_entry e
          join ranking r on r.id=e.ranking_id
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          where e.ranking_id=:ranking and e.entry_id=:entry and e.removed_at is null
            and (r.owner_user_id=:actor or exists (
              select 1 from ranking_membership_grant g
              where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        )
        select e.entry_id, a.total, a.votes, exists (
          select 1 from ranking_movie_round t
          where t.ranking_id=e.ranking_id and t.entry_id=e.entry_id and t.revealed is not true
        ) as partial
        from eligible e cross join lateral (
          select coalesce(sum(n.score::numeric),0) as total,count(n.score)::numeric as votes
          from ranking_movie_round t
          join ranking_movie_rating n on n.ranking_id=t.ranking_id and n.round_id=t.round_id
          where t.ranking_id=e.ranking_id and t.entry_id=e.entry_id and t.revealed is true
        ) a
        """, Map.of("ranking",rankingId,"entry",entryId,"actor",actorUserId),
        (row,index)->new EntryRatingAggregateView(row.getObject("entry_id",UUID.class),
            row.getBigDecimal("total").toBigIntegerExact(),row.getBigDecimal("votes").toBigIntegerExact(),
            row.getBoolean("partial")));
    return rows.stream().findFirst();
  }
}
