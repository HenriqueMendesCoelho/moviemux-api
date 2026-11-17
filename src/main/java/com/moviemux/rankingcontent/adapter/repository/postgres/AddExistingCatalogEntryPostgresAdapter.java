package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAuthorIdentities;
import com.moviemux.rankingcontent.domain.exception.ArchivedCatalogEntryException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.port.AddExistingCatalogEntry;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AddExistingCatalogEntryPostgresAdapter implements AddExistingCatalogEntry {
  private final NamedParameterJdbcTemplate jdbc;
  private final RankingAuthorIdentities authors;
  public AddExistingCatalogEntryPostgresAdapter(NamedParameterJdbcTemplate jdbc, RankingAuthorIdentities authors) {
    this.jdbc=Objects.requireNonNull(jdbc);this.authors=Objects.requireNonNull(authors);
  }
  private record LockedRanking(UUID owner,boolean archived) {}
  @Override @Transactional(propagation=Propagation.MANDATORY)
  public AddedCatalogEntry write(UUID rankingId,UUID actorUserId,UUID movieId) {
    var params=Map.of("ranking",Objects.requireNonNull(rankingId),"actor",Objects.requireNonNull(actorUserId),"movie",Objects.requireNonNull(movieId));
    var ranks=jdbc.query("select owner_user_id,archived_at from ranking where id=:ranking for update",params,
        (row,n)->new LockedRanking(row.getObject("owner_user_id",UUID.class),row.getObject("archived_at")!=null));
    if(ranks.isEmpty())throw new RankingEntriesDeniedException();
    var members=jdbc.query("select active from ranking_membership where ranking_id=:ranking and user_id=:actor for update",params,(row,n)->row.getBoolean("active"));
    if(members.isEmpty() || !members.get(0))throw new RankingEntriesDeniedException();
    var rank=ranks.get(0);
    if(!rank.owner().equals(actorUserId) && !Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from ranking_membership_grant where ranking_id=:ranking and user_id=:actor and capability='ADD_ENTRY')
        """,params,Boolean.class)))throw new RankingEntriesDeniedException();
    if(rank.archived())throw new ArchivedCatalogEntryException();
    // No provider call, catalog insert/update, legacy creation usecase, or global notification.
    var movies=jdbc.query("select id from movie where id=:movie for key share",params,(row,n)->row.getObject("id",UUID.class));
    if(movies.isEmpty())throw new RankingEntriesDeniedException();
    var existing=jdbc.query("select entry_id from ranking_movie_entry where ranking_id=:ranking and movie_id=:movie",params,(row,n)->row.getObject("entry_id",UUID.class));
    if(!existing.isEmpty())return new AddedCatalogEntry(existing.get(0),false);
    var author=authors.getOrCreate(rankingId,actorUserId);
    if(!rankingId.equals(author.rankingId()) || !actorUserId.equals(author.accountId()))throw new RankingEntriesDeniedException();
    UUID entry=UUID.randomUUID(),round=UUID.randomUUID();
    // Start the contextual clock at insertion, not at transaction start before a possible lock wait.
    // One statement shares the exact timestamp between entry and first round.
    jdbc.update("""
        with inserted as (
          insert into ranking_movie_entry(ranking_id,entry_id,movie_id,added_by_author_id,created_at)
          values (:ranking,:entry,:movie,:author,clock_timestamp())
          returning ranking_id,entry_id,created_at
        )
        insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,description,created_at,revealed)
        select ranking_id,entry_id,:round,1,null,created_at,false from inserted
        """,Map.of("ranking",rankingId,"entry",entry,"movie",movieId,"author",author.authorId(),"round",round));
    return new AddedCatalogEntry(entry,true);
  }
}
