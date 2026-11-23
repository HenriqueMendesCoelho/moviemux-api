package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.exception.ArchivedEntryRecoveryException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RecoverableEntry;
import com.moviemux.rankingcontent.domain.port.ListRecoverableEntries;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListRecoverableEntriesPostgresAdapter implements ListRecoverableEntries {
  private final NamedParameterJdbcTemplate jdbc;
  public ListRecoverableEntriesPostgresAdapter(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

  @Override public List<RecoverableEntry> find(UUID actorId,UUID rankingId,UUID after,int maxRows) {
    if(maxRows<1 || maxRows>51) throw new IllegalArgumentException("Invalid recovery page bound");
    // Authority, lifecycle, pagination and title visibility use one scope-consistent snapshot.
    // Actors with own-only removal/view rights are filtered before any page limit or title mapping.
    return jdbc.query("""
        with authority as (
          select r.id, r.archived_at is not null as archived, r.owner_user_id=:actor as own_rank,
            exists(select 1 from ranking_membership_grant g where g.ranking_id=r.id and g.user_id=:actor
              and g.capability='REMOVE_ENTRY') as remove_any,
            exists(select 1 from ranking_membership_grant g where g.ranking_id=r.id and g.user_id=:actor
              and g.capability='REMOVE_OWN_ENTRY') as remove_own,
            exists(select 1 from ranking_membership_grant g where g.ranking_id=r.id and g.user_id=:actor
              and g.capability='READ') as can_read
          from ranking r
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
          where r.id=:rank
        ), allowed as (
          select * from authority where own_rank or remove_any or remove_own
        ), page as (
          select e.entry_id,e.removed_at,e.removed_at+interval '24 hours' as expires_at,e.lifecycle_version,
            c.original_title,c.english_title,c.portuguese_title
          from allowed a join ranking_movie_entry e on e.ranking_id=a.id
          left join ranking_author_identity owner on owner.ranking_id=e.ranking_id and owner.author_id=e.added_by_author_id
          left join movie c on c.id=e.movie_id and (a.own_rank or a.can_read)
          where not a.archived and (a.own_rank or a.remove_any or (a.remove_own and owner.account_id=:actor))
            and e.removed_at is not null and statement_timestamp()<e.removed_at+interval '24 hours'
            and (cast(:after as uuid) is null or e.entry_id>cast(:after as uuid))
          order by e.entry_id limit :maxRows
        )
        select a.archived,p.* from allowed a left join page p on true order by p.entry_id
        """,new MapSqlParameterSource().addValue("actor",actorId).addValue("rank",rankingId)
          .addValue("after",after).addValue("maxRows",maxRows),rows->{
      if(!rows.next()) throw new RankingEntriesDeniedException();
      if(rows.getBoolean("archived")) throw new ArchivedEntryRecoveryException();
      var result=new ArrayList<RecoverableEntry>();
      do {
        UUID entry=rows.getObject("entry_id",UUID.class);
        if(entry!=null) result.add(new RecoverableEntry(entry,
            title(rows.getString("original_title"),rows.getString("english_title"),rows.getString("portuguese_title")),
            rows.getObject("removed_at",OffsetDateTime.class),rows.getObject("expires_at",OffsetDateTime.class),
            rows.getLong("lifecycle_version")));
      }while(rows.next());
      return List.copyOf(result);
    });
  }

  private String title(String... texts){
    for(var text:texts) if(text!=null && text.codePoints().anyMatch(cp->!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)))
      return text;
    return null;
  }
}
