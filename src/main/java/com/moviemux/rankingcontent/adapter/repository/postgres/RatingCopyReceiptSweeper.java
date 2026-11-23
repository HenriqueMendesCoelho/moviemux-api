package com.moviemux.rankingcontent.adapter.repository.postgres;

import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cleans only expired receipt rows; database time cannot be overridden by a request or scheduler. */
public final class RatingCopyReceiptSweeper {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public RatingCopyReceiptSweeper(DataSource source) {
    jdbc=new JdbcTemplate(Objects.requireNonNull(source));
    transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setTimeout(10);
  }

  public Sweep sweep(int limit) {
    if(limit<1 || limit>100 || TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalArgumentException("Invalid independent receipt sweep");
    return Objects.requireNonNull(transaction.execute(status->{
      jdbc.execute("set local lock_timeout='2s'");
      jdbc.execute("set local statement_timeout='8s'");
      int removed=jdbc.update("""
          with expired as materialized (
            select operation_id from ranking_rating_copy_receipt where expires_at<=statement_timestamp()
            order by expires_at,operation_id limit ? for update skip locked
          )
          delete from ranking_rating_copy_receipt c using expired where c.operation_id=expired.operation_id
          """,limit);
      long boundedCount=jdbc.queryForObject("""
          select count(*) from (select operation_id from ranking_rating_copy_receipt
          where expires_at<=statement_timestamp() order by expires_at,operation_id limit ?) remaining
          """,Long.class,limit+1);
      double oldestLag=jdbc.queryForObject("""
          select coalesce((select extract(epoch from statement_timestamp()-expires_at)
            from ranking_rating_copy_receipt where expires_at<=statement_timestamp()
            order by expires_at,operation_id limit 1),0)
          """,Double.class);
      return new Sweep(removed,boundedCount,oldestLag);
    }));
  }

  public record Sweep(int removed,long boundedRemaining,double oldestLagSeconds) {}
}
