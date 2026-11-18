package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.CurrentRankingEntries;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CurrentRankingEntriesPostgresAdapter implements CurrentRankingEntries {
  private final NamedParameterJdbcTemplate jdbc;
  public CurrentRankingEntriesPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
  @Override public Optional<RankingEntriesSnapshot> find(UUID rankingId, UUID actorUserId, UUID after, int maxRows) {
    Objects.requireNonNull(rankingId);Objects.requireNonNull(actorUserId);
    if(maxRows<1 || maxRows>101)throw new IllegalArgumentException("Invalid entry query bound");
    return jdbc.query("""
        with allowed as (
          select r.id as ranking_id, m.user_id as actor_user_id
          from ranking r join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          where r.id=:ranking and (r.owner_user_id=:actor or exists (
            select 1 from ranking_membership_grant g
            where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ'))
        ), page as (
          select e.entry_id, e.movie_id, m.tmdb_id, m.original_title, m.english_title,
                 m.portuguese_title, m.url_image, m.release_date, m.runtime, e.created_at
          from allowed a join ranking_movie_entry e on e.ranking_id=a.ranking_id
          join movie m on m.id=e.movie_id
          where e.removed_at is null and (cast(:after as uuid) is null or e.entry_id>cast(:after as uuid))
          order by e.entry_id limit :maxRows
        )
        select a.ranking_id, a.actor_user_id, p.* from allowed a left join page p on true
        order by p.entry_id
        """,new MapSqlParameterSource().addValue("ranking",rankingId).addValue("actor",actorUserId)
        .addValue("after",after).addValue("maxRows",maxRows),rows->{
      if(!rows.next())return Optional.empty();
      UUID scope=rows.getObject("ranking_id",UUID.class),actor=rows.getObject("actor_user_id",UUID.class);
      var items=new ArrayList<RankingMovieEntryView>();
      do {
        UUID entry=rows.getObject("entry_id",UUID.class);
        // The left-join sentinel distinguishes allowed empty pages from denied/missing rankings.
        if(entry!=null)items.add(new RankingMovieEntryView(entry,rows.getObject("movie_id",UUID.class),
            rows.getObject("tmdb_id",Long.class),rows.getString("original_title"),rows.getString("english_title"),
            rows.getString("portuguese_title"),rows.getString("url_image"),rows.getObject("release_date",LocalDate.class),
            rows.getObject("runtime",Integer.class),rows.getObject("created_at",OffsetDateTime.class)));
      }while(rows.next());
      return Optional.of(new RankingEntriesSnapshot(scope,actor,items));
    });
  }
}
