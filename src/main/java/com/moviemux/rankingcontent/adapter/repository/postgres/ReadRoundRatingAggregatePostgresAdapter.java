package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.RoundRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.ReadRoundRatingAggregate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadRoundRatingAggregatePostgresAdapter implements ReadRoundRatingAggregate {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadRoundRatingAggregatePostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override public Optional<RoundRatingAggregateView> find(UUID rank, UUID entry, UUID round, UUID actor) {
    // Authorization, disclosure and sum/count all share one snapshot. The correlated
    // hidden branch does not contribute values, even when the actor owns a hidden vote.
    return jdbc.query("""
        with eligible as (
          select t.ranking_id,t.round_id,t.revealed
          from ranking_movie_round t
          join ranking_movie_entry e on e.ranking_id=t.ranking_id and e.entry_id=t.entry_id and e.removed_at is null
          join ranking r on r.id=t.ranking_id
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
          where t.ranking_id=:rank and t.entry_id=:entry and t.round_id=:round
            and (r.owner_user_id=:actor or exists(select 1 from ranking_membership_grant g
              where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        )
        select e.round_id,e.revealed,a.total,a.votes
        from eligible e left join lateral (
          select coalesce(sum(n.score::numeric),0) as total,count(n.score)::numeric as votes
          from ranking_movie_rating n
          where e.revealed is true and n.ranking_id=e.ranking_id and n.round_id=e.round_id
        ) a on e.revealed is true
        """, Map.of("rank",rank,"entry",entry,"round",round,"actor",actor),
        (row,index)->new RoundRatingAggregateView(row.getObject("round_id",UUID.class),
          row.getObject("revealed",Boolean.class), integer(row.getBigDecimal("total")), integer(row.getBigDecimal("votes"))))
        .stream().findFirst();
  }

  private static BigInteger integer(BigDecimal value) { return value == null ? null : value.toBigIntegerExact(); }
}
