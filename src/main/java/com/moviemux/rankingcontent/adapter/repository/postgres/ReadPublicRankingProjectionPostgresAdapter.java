package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.PublicRankingEntry;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import com.moviemux.rankingcontent.domain.port.ReadPublicRankingProjection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadPublicRankingProjectionPostgresAdapter implements ReadPublicRankingProjection {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadPublicRankingProjectionPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public Optional<PublicRankingSummary> summary(UUID rankingId) {
    return jdbc.query("""
        select id, name from ranking where id=:ranking and visibility='PUBLIC' and archived_at is null
        """, Map.of("ranking",rankingId),
        (row,index)->new PublicRankingSummary(row.getObject("id",UUID.class),row.getString("name")))
        .stream().findFirst();
  }

  @Override public Optional<List<PublicRankingEntry>> entries(UUID rankingId,UUID after,int maxRows) {
    if (maxRows<1 || maxRows>51) throw new IllegalArgumentException("Invalid public ranking page bound");
    // One snapshot authorizes live PUBLIC visibility, pages by opaque entry IDs, and counts only disclosed
    // ratings. Hidden/unknown states and viewer/account identity do not influence any public result.
    return jdbc.query("""
        with eligible as (
          select id from ranking where id=:ranking and visibility='PUBLIC' and archived_at is null
        ), page as (
          select e.ranking_id,e.entry_id,m.original_title,m.english_title,m.portuguese_title
          from eligible r join ranking_movie_entry e on e.ranking_id=r.id and e.removed_at is null
          join movie m on m.id=e.movie_id
          where cast(:after as uuid) is null or e.entry_id>cast(:after as uuid)
          order by e.entry_id limit :maxRows
        )
        select p.entry_id,p.original_title,p.english_title,p.portuguese_title,a.total,a.votes
        from eligible r left join page p on true
        left join lateral (
          select coalesce(sum(n.score::numeric),0) as total,count(n.score)::numeric as votes
          from ranking_movie_round t join ranking_movie_rating n
            on n.ranking_id=t.ranking_id and n.round_id=t.round_id
          where t.ranking_id=p.ranking_id and t.entry_id=p.entry_id and t.revealed is true
        ) a on p.entry_id is not null
        order by p.entry_id
        """,new MapSqlParameterSource().addValue("ranking",rankingId).addValue("after",after).addValue("maxRows",maxRows),
        rows->{
          if(!rows.next()) return Optional.empty();
          var page=new ArrayList<PublicRankingEntry>();
          do {
            UUID entry=rows.getObject("entry_id",UUID.class);
            if(entry!=null) page.add(new PublicRankingEntry(entry,
                firstTitle(rows.getString("original_title"),rows.getString("english_title"),rows.getString("portuguese_title")),
                rows.getBigDecimal("total").toBigIntegerExact(),rows.getBigDecimal("votes").toBigIntegerExact()));
          } while(rows.next());
          return Optional.of(List.copyOf(page));
        });
  }

  private static String firstTitle(String... candidates) {
    for (var candidate:candidates) {
      if(candidate!=null && candidate.codePoints().anyMatch(cp->!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)))
        return candidate;
    }
    return null;
  }
}
