package com.moviemux.rankingcontent.adapter.repository.postgres;
import com.moviemux.rankingcontent.domain.model.RankingMovieRoundListItem;
import com.moviemux.rankingcontent.domain.port.RankingMovieRoundListPort;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
@Repository
public class RankingMovieRoundListPostgresAdapter implements RankingMovieRoundListPort {
  private final NamedParameterJdbcTemplate jdbc;
  public RankingMovieRoundListPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
  @Override public Optional<List<RankingMovieRoundListItem>> find(UUID actor, UUID ranking, UUID entry, Long afterNumber, int maxRows) {
    Objects.requireNonNull(actor); Objects.requireNonNull(ranking); Objects.requireNonNull(entry);
    if (maxRows < 1 || maxRows > 101 || (afterNumber != null && afterNumber < 1)) throw new IllegalArgumentException("Invalid page bounds");
    return jdbc.query("""
        with eligible as (
          select e.ranking_id, e.entry_id from ranking_movie_entry e
          join ranking r on r.id=e.ranking_id
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          where e.ranking_id=:ranking and e.entry_id=:entry
            and (r.owner_user_id=:actor or exists (
              select 1 from ranking_membership_grant g
              where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        )
        select rounds.round_id, rounds.round_number, rounds.description, rounds.created_at, rounds.updated_at, rounds.revealed
        from eligible e left join lateral (
          select rr.round_id, rr.round_number, rr.description, rr.created_at, rr.updated_at, rr.revealed
          from ranking_movie_round rr where rr.ranking_id=e.ranking_id and rr.entry_id=e.entry_id
            and (cast(:afterNumber as bigint) is null or rr.round_number > cast(:afterNumber as bigint))
          order by rr.round_number limit :maxRows
        ) rounds on true order by rounds.round_number
        """, new MapSqlParameterSource().addValue("actor",actor).addValue("ranking",ranking).addValue("entry",entry)
        .addValue("afterNumber",afterNumber).addValue("maxRows",maxRows), rows -> {
      if (!rows.next()) return Optional.empty();
      var result = new ArrayList<RankingMovieRoundListItem>();
      do {
        UUID id=rows.getObject("round_id",UUID.class);
        if (id == null) continue;
        var updated=rows.getTimestamp("updated_at");
        result.add(new RankingMovieRoundListItem(id,rows.getLong("round_number"),rows.getString("description"),
            rows.getTimestamp("created_at").toInstant(),updated == null ? null : updated.toInstant(),rows.getObject("revealed",Boolean.class)));
      } while (rows.next());
      return Optional.of(List.copyOf(result));
    });
  }
}
