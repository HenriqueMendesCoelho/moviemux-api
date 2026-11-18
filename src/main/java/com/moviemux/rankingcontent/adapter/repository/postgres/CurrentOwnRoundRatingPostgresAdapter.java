package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import com.moviemux.rankingcontent.domain.port.CurrentOwnRoundRating;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Authorizes and reads only the principal's own rating in one database snapshot. */
@Repository
public class CurrentOwnRoundRatingPostgresAdapter implements CurrentOwnRoundRating {
  private final NamedParameterJdbcTemplate jdbc;
  public CurrentOwnRoundRatingPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public Optional<OwnRoundRatingView> find(UUID rankingId,UUID roundId,UUID actorUserId) {
    var rows=jdbc.query("""
        select n.round_id,n.score
        from ranking r
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join ranking_movie_round t on t.ranking_id=r.id and t.round_id=:round
        join ranking_movie_entry e on e.ranking_id=t.ranking_id and e.entry_id=t.entry_id and e.removed_at is null
        join ranking_author_identity a on a.ranking_id=r.id and a.account_id=:actor
        join ranking_movie_rating n on n.ranking_id=t.ranking_id and n.round_id=t.round_id and n.author_id=a.author_id
        where r.id=:ranking and (r.owner_user_id=:actor or exists (
          select 1 from ranking_membership_grant g
          where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        """, Map.of("ranking",rankingId,"round",roundId,"actor",actorUserId),
        (row,index)->new OwnRoundRatingView(row.getObject("round_id",UUID.class),row.getObject("score",Integer.class)));
    return rows.stream().findFirst();
  }
}
