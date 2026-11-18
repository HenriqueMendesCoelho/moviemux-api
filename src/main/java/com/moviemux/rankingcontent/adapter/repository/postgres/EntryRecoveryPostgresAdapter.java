package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import com.moviemux.rankingcontent.domain.port.EntryRecovery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class EntryRecoveryPostgresAdapter implements EntryRecovery {
  private final NamedParameterJdbcTemplate jdbc;
  public EntryRecoveryPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
  private static final String STATE = "e.entry_id,e.lifecycle_version,e.removed_at,e.removal_token,e.removed_at + interval '24 hours' as expires_at";
  private static EntryRecoveryState state(ResultSet row) throws SQLException {
    return new EntryRecoveryState(row.getObject("entry_id",UUID.class),row.getLong("lifecycle_version"),
        row.getObject("removed_at",OffsetDateTime.class),row.getObject("expires_at",OffsetDateTime.class),row.getObject("removal_token",UUID.class));
  }
  private Map<String,Object> params(UUID rankingId,UUID entryId,UUID actorUserId) {
    var p=new HashMap<String,Object>();p.put("ranking",rankingId);p.put("entry",entryId);p.put("actor",actorUserId);return p;
  }
  @Override public EntryRecoveryState preview(UUID rankingId,UUID entryId,UUID actorUserId) {
    // One snapshot binds membership, ownership and entry authorship; no READ grant is implied.
    var rows=jdbc.query("select "+STATE+",r.archived_at from ranking r join ranking_membership m on m.ranking_id=r.id "
        +"join ranking_movie_entry e on e.ranking_id=r.id left join ranking_author_identity a on a.ranking_id=e.ranking_id and a.author_id=e.added_by_author_id "
        +"where r.id=:ranking and e.entry_id=:entry and m.user_id=:actor and m.active and (r.owner_user_id=:actor or exists ("
        +"select 1 from ranking_membership_grant g where g.ranking_id=r.id and g.user_id=:actor and (g.capability='REMOVE_ENTRY' or (g.capability='REMOVE_OWN_ENTRY' and a.account_id=:actor))))",
        params(rankingId,entryId,actorUserId),(row,n)->new AuthorizedState(state(row),row.getObject("archived_at")!=null));
    if(rows.isEmpty())throw new RankingEntriesDeniedException();
    if(rows.get(0).archived())throw new ArchivedEntryRecoveryException();
    return rows.get(0).state();
  }
  private record AuthorizedState(EntryRecoveryState state,boolean archived) {}
  private record LockedRanking(UUID owner,boolean archived) {}
  private record LockedEntry(EntryRecoveryState state,UUID account) {}
  private EntryRecoveryState lockAuthorized(Map<String,Object> p) {
    var ranks=jdbc.query("select owner_user_id,archived_at from ranking where id=:ranking for update",p,
        (row,n)->new LockedRanking(row.getObject("owner_user_id",UUID.class),row.getObject("archived_at")!=null));
    if(ranks.isEmpty())throw new RankingEntriesDeniedException();
    var members=jdbc.query("select active from ranking_membership where ranking_id=:ranking and user_id=:actor for update",p,(row,n)->row.getBoolean("active"));
    if(members.isEmpty() || !members.get(0))throw new RankingEntriesDeniedException();
    var entries=jdbc.query("select "+STATE+",a.account_id from ranking_movie_entry e left join ranking_author_identity a on a.ranking_id=e.ranking_id and a.author_id=e.added_by_author_id where e.ranking_id=:ranking and e.entry_id=:entry for update of e",p,
        (row,n)->new LockedEntry(state(row),row.getObject("account_id",UUID.class)));
    if(entries.isEmpty())throw new RankingEntriesDeniedException();
    var entry=entries.get(0);var rank=ranks.get(0);
    if(!rank.owner().equals(p.get("actor"))) {
      var grants=jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=:ranking and user_id=:actor",p,String.class);
      if(!grants.contains("REMOVE_ENTRY") && !(p.get("actor").equals(entry.account()) && grants.contains("REMOVE_OWN_ENTRY")))throw new RankingEntriesDeniedException();
    }
    if(rank.archived())throw new ArchivedEntryRecoveryException();
    return entry.state();
  }
  @Override public EntryRecoveryState remove(UUID rankingId,UUID entryId,UUID actorUserId,long expectedVersion) {
    var p=params(rankingId,entryId,actorUserId);var current=lockAuthorized(p);
    if(current.version()!=expectedVersion || current.removedAt()!=null)throw new StaleEntryRecoveryException();
    p.put("token",UUID.randomUUID());
    var rows=jdbc.query("update ranking_movie_entry e set removed_at=clock_timestamp(),removal_token=:token,lifecycle_version=lifecycle_version+1 where ranking_id=:ranking and entry_id=:entry returning "+STATE,p,(row,n)->state(row));
    return rows.get(0);
  }
  @Override public EntryRecoveryState restore(UUID rankingId,UUID entryId,UUID actorUserId,UUID removalToken) {
    var p=params(rankingId,entryId,actorUserId);var current=lockAuthorized(p);
    if(current.removedAt()==null || !removalToken.equals(current.removalToken()))throw new StaleEntryRecoveryException();
    var rows=jdbc.query("update ranking_movie_entry e set removed_at=null,removal_token=null,lifecycle_version=lifecycle_version+1 where ranking_id=:ranking and entry_id=:entry and clock_timestamp()<removed_at+interval '24 hours' returning "+STATE,p,(row,n)->state(row));
    if(rows.isEmpty())throw new ExpiredEntryRecoveryException();
    return rows.get(0);
  }
}
