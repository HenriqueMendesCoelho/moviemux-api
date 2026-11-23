package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.exception.ArchivedCatalogEntryException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.CatalogMovieSearchPage.Item;
import com.moviemux.rankingcontent.domain.port.SearchCatalogMovies;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchCatalogMoviesPostgresAdapter implements SearchCatalogMovies {
  // All Java whitespace / space characters are in the BMP. This also covers NBSP and em spaces,
  // which are not uniformly part of the database locale's POSIX space class.
  private static final String TITLE_EDGE_WHITESPACE = IntStream.rangeClosed(1, Character.MAX_VALUE)
      .filter(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))
      .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  private final NamedParameterJdbcTemplate jdbc;

  public SearchCatalogMoviesPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public List<Item> find(UUID rankingId, UUID actorId, String query, String language, int offset, int maxRows) {
    // Drive the result from current scope authorization. The lateral page is never an
    // unscoped preflight, and a left-join sentinel distinguishes authenticated empty from denied.
    return jdbc.query("""
        with allowed as materialized (
          select r.archived_at is not null as archived
          from ranking r
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
          where r.id=:rank and (r.owner_user_id=:actor or exists (
            select 1 from ranking_membership_grant g
            where g.ranking_id=r.id and g.user_id=:actor and g.capability='ADD_ENTRY'))
        )
        select a.archived, p.movie_id, p.title, p.release_date
        from allowed a left join lateral (
          select m.id as movie_id, titles.display_title as title, m.release_date
          from movie m
          cross join lateral (
            select nullif(btrim(m.portuguese_title, :space), '') as pt,
                   nullif(btrim(m.original_title, :space), '') as original,
                   nullif(btrim(m.english_title, :space), '') as en
          ) t
          cross join lateral (
            select case when :language='pt-BR' then coalesce(t.pt,t.original,t.en)
                        else coalesce(t.en,t.original,t.pt) end as display_title
          ) titles
          where not a.archived and titles.display_title is not null
            and (strpos(lower(t.pt), lower(:query))>0
              or strpos(lower(t.original), lower(:query))>0
              or strpos(lower(t.en), lower(:query))>0)
          order by lower(titles.display_title) collate "C", m.id
          limit :maxRows offset :offsetRows
        ) p on true
        order by lower(p.title) collate "C", p.movie_id
        """, new MapSqlParameterSource().addValue("actor", actorId).addValue("rank", rankingId)
        .addValue("query", query).addValue("language", language).addValue("space", TITLE_EDGE_WHITESPACE)
        .addValue("offsetRows", offset).addValue("maxRows", maxRows), rows -> {
      if (!rows.next()) throw new RankingEntriesDeniedException();
      if (rows.getBoolean("archived")) throw new ArchivedCatalogEntryException();
      var result = new ArrayList<Item>();
      do {
        UUID id = rows.getObject("movie_id", UUID.class);
        if (id != null) result.add(new Item(id, rows.getString("title"), rows.getObject("release_date", LocalDate.class)));
      } while (rows.next());
      return List.copyOf(result);
    });
  }
}
