package com.moviemux.rankingcontent.adapter.repository.postgres;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator primitive, deliberately not a bean, endpoint, startup hook or scheduler.
 * Call only with an isolated/approved DataSource. No caller-provided time can shorten retention.
 */
public final class EntryExpiryWorker {
  private final JdbcTemplate jdbc;
  private final DataSourceTransactionManager manager;

  public EntryExpiryWorker(DataSource source) {
    jdbc = new JdbcTemplate(Objects.requireNonNull(source));
    manager = new DataSourceTransactionManager(source);
  }

  /** Purges at most limit contextual entries in one transaction. Busy rankings are skipped. */
  public int runBatch(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("ranking.invalid_expiry_batch");
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("ranking.expiry_requires_independent_transaction");
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(30);
    return Objects.requireNonNull(tx.execute(status -> purge(limit)));
  }

  private int purge(int limit) {
    jdbc.execute("set local lock_timeout='2s'");
    jdbc.execute("set local statement_timeout='30s'");
    // All contextual mutation paths acquire ranking before entry. SKIP LOCKED never waits
    // behind a restore holding the ranking lock; another pass will inspect the fresh state.
    var ranks = jdbc.queryForList("""
        select r.id from ranking r where exists (
          select 1 from ranking_movie_entry e where e.ranking_id=r.id
            and e.removed_at is not null and e.removed_at <= clock_timestamp()-interval '24 hours')
        order by r.id limit ? for update of r skip locked
        """, UUID.class, limit);
    int purged = 0;
    for (UUID ranking : ranks) {
      var entries = jdbc.queryForList("""
          select entry_id from ranking_movie_entry where ranking_id=? and removed_at is not null
            and removed_at <= clock_timestamp()-interval '24 hours'
          order by removed_at,entry_id limit ? for update skip locked
          """, UUID.class, ranking, limit - purged);
      for (UUID entry : entries) {
        // A new READ_COMMITTED statement after acquisition is authoritative, not candidate selection.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from ranking_movie_entry where ranking_id=? and entry_id=?
              and removed_at is not null and removed_at <= clock_timestamp()-interval '24 hours')
            """, Boolean.class, ranking, entry))) continue;
        // Keep original identifiers, hashes and counts as a terminal provenance receipt.
        jdbc.update("""
            update legacy_ranking_migration set replay_closed_at=coalesce(replay_closed_at,clock_timestamp())
            where ranking_id=? and exists(select 1 from legacy_ranking_movie_map m
              where m.ranking_id=? and m.live_entry_id=?)
            """, ranking, ranking, entry);
        jdbc.update("""
            update legacy_ranking_movie_map set live_entry_id=null,live_round_id=null,purged_at=clock_timestamp()
            where ranking_id=? and live_entry_id=?
            """, ranking, entry);
        jdbc.update("""
            delete from ranking_movie_rating n using ranking_movie_round r
            where n.ranking_id=r.ranking_id and n.round_id=r.round_id
              and r.ranking_id=? and r.entry_id=?
            """, ranking, entry);
        jdbc.update("delete from ranking_movie_round where ranking_id=? and entry_id=?", ranking, entry);
        int removed = jdbc.update("delete from ranking_movie_entry where ranking_id=? and entry_id=?", ranking, entry);
        if (removed != 1) throw new IllegalStateException("ranking.expiry_state_changed");
        purged++;
      }
      if (purged == limit) break;
    }
    return purged;
  }
}
