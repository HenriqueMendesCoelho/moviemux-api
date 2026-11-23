package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidEntryRecoveryException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RecoverableEntriesPage;
import com.moviemux.rankingcontent.domain.model.RestoredEntry;
import com.moviemux.rankingcontent.domain.port.ListRecoverableEntries;
import com.moviemux.rankingcontent.domain.port.RestoreCurrentEntry;
import com.moviemux.rankingcontent.domain.usecase.ManageRecoverableEntriesUseCase;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageRecoverableEntriesUseCaseImpl implements ManageRecoverableEntriesUseCase {
  private static final Duration RETENTION = Duration.ofHours(24);
  private final ListRecoverableEntries recoverable;
  private final RestoreCurrentEntry restores;

  public ManageRecoverableEntriesUseCaseImpl(ListRecoverableEntries recoverable, RestoreCurrentEntry restores) {
    this.recoverable = Objects.requireNonNull(recoverable, "recoverable");
    this.restores = Objects.requireNonNull(restores, "restores");
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public RecoverableEntriesPage list(RankingActor actor, UUID rankingId, UUID after, int limit) {
    UUID account = actorId(actor, rankingId);
    if (limit < 1 || limit > MAX_LIMIT) throw new InvalidEntryRecoveryException();
    var found = recoverable.find(account, rankingId, after, limit + 1);
    if (found == null || found.size() > limit + 1) throw new RankingEntriesDeniedException();
    UUID previous = after;
    for (var item : found) {
      if (item == null || item.entryId() == null || item.version() < 0 || item.removedAt() == null
          || item.expiresAt() == null || !RETENTION.equals(Duration.between(item.removedAt(), item.expiresAt()))
          || (previous != null && previous.toString().compareTo(item.entryId().toString()) >= 0))
        throw new RankingEntriesDeniedException();
      previous = item.entryId();
    }
    var items = found.subList(0, Math.min(limit, found.size()));
    return new RecoverableEntriesPage(items, found.size() > limit ? items.get(items.size() - 1).entryId() : null);
  }

  @Override
  public RestoredEntry restoreCurrent(RankingActor actor, UUID rankingId, UUID entryId, long expectedVersion) {
    UUID account = actorId(actor, rankingId);
    if (entryId == null) throw new RankingEntriesDeniedException();
    if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) throw new InvalidEntryRecoveryException();
    var result = restores.restoreCurrent(rankingId, entryId, account, expectedVersion);
    if (result == null || !entryId.equals(result.entryId()) || result.version() != expectedVersion + 1)
      throw new RankingEntriesDeniedException();
    return result;
  }

  private UUID actorId(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingEntriesDeniedException();
    return actor.userId();
  }
}
