package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import com.moviemux.ranking.domain.port.AllocateRankingAuthorIdentity;
import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class RankingAuthorIdentityPostgresAdapter implements AllocateRankingAuthorIdentity {
  private static final int MAX_ATTEMPTS = 8;
  private final NamedParameterJdbcTemplate jdbc;
  private final Supplier<UUID> identifiers;

  @Autowired
  public RankingAuthorIdentityPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this(jdbc, UUID::randomUUID);
  }

  public RankingAuthorIdentityPostgresAdapter(NamedParameterJdbcTemplate jdbc, Supplier<UUID> identifiers) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RankingAuthorIdentity getOrCreate(UUID rankingId, UUID accountId) {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(accountId, "accountId");
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Author allocation requires an existing transaction");
    Integer isolation = jdbc.getJdbcTemplate().execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
    if (!Objects.equals(isolation, Connection.TRANSACTION_READ_COMMITTED))
      throw new IllegalStateException("Author allocation requires READ_COMMITTED");
    var lookup = Map.of("ranking", rankingId, "account", accountId);
    var existing = find(lookup);
    if (existing != null) return existing;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      UUID author = Objects.requireNonNull(identifiers.get(), "generated author identity");
      UUID code = Objects.requireNonNull(identifiers.get(), "generated public code");
      // Even a forced random collision must never expose the original account identifier.
      if (author.equals(code) || author.equals(accountId) || code.equals(accountId)) continue;
      jdbc.update("""
          insert into ranking_author_identity(ranking_id, author_id, account_id, deleted_public_code)
          select :ranking, :author, :account, :code from ranking_membership
          where ranking_id=:ranking and user_id=:account
          on conflict do nothing
          """, Map.of("ranking", rankingId, "account", accountId, "author", author, "code", code));
      // READ_COMMITTED gives this statement a new snapshot after a competing insert commits.
      existing = find(lookup);
      if (existing != null) return existing;
      Boolean member = jdbc.queryForObject("""
          select exists(select 1 from ranking_membership where ranking_id=:ranking and user_id=:account)
          """, lookup, Boolean.class);
      if (!Boolean.TRUE.equals(member)) throw new IllegalArgumentException("Contextual membership required");
    }
    throw new IllegalStateException("Unable to allocate contextual author identity");
  }

  private RankingAuthorIdentity find(Map<String, UUID> lookup) {
    var rows = jdbc.query("""
        select ranking_id, author_id, account_id, deleted_public_code from ranking_author_identity
        where ranking_id=:ranking and account_id=:account
        """, lookup, (row, index) -> new RankingAuthorIdentity(
            row.getObject("ranking_id", UUID.class), row.getObject("author_id", UUID.class),
            row.getObject("account_id", UUID.class), row.getObject("deleted_public_code", UUID.class)));
    return rows.isEmpty() ? null : rows.get(0);
  }
}
