package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.LegacyFilmLink;
import com.moviemux.rankingcontent.domain.port.ReadLegacyFilmLink;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadLegacyFilmLinkPostgresAdapter implements ReadLegacyFilmLink {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadLegacyFilmLinkPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override public Optional<LegacyFilmLink> find(UUID actorId, UUID legacyMovieId) {
    // Never resolve by movie_id alone. Both live mapping keys, original catalog identity,
    // actor authority and authoritative phase must agree in this statement's snapshot.
    return jdbc.query("""
        select r.id as ranking_id, e.entry_id
        from legacy_ranking_movie_map p
        join ranking r on r.id=p.ranking_id and r.archived_at is null
        join ranking_movie_entry e on e.ranking_id=r.id and e.entry_id=p.live_entry_id
           and e.movie_id=p.legacy_movie_id and e.removed_at is null
        join ranking_movie_round d on d.ranking_id=r.id and d.round_id=p.live_round_id and d.entry_id=e.entry_id
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        where p.migration_key='legacy-movies-v1' and p.legacy_movie_id=:movie and p.purged_at is null
          and is_active_legacy_board_destination(p.ranking_id)
          and (r.owner_user_id=:actor or exists (
             select 1 from ranking_membership_grant g
             where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        limit 1
        """, new MapSqlParameterSource().addValue("actor", actorId).addValue("movie", legacyMovieId), rows ->
        rows.next() ? Optional.of(new LegacyFilmLink(rows.getObject("ranking_id", UUID.class),
            rows.getObject("entry_id", UUID.class))) : Optional.empty());
  }
}
