package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.rankingcontent.domain.exception.InvalidPublicRankingPageException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.PublicRankingEntriesPage;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import com.moviemux.rankingcontent.domain.port.ReadPublicRankingProjection;
import com.moviemux.rankingcontent.domain.usecase.ReadPublicRankingUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class ReadPublicRankingUseCaseImpl implements ReadPublicRankingUseCase {
  private final ReadPublicRankingProjection rankings;

  public ReadPublicRankingUseCaseImpl(ReadPublicRankingProjection rankings) {
    this.rankings = Objects.requireNonNull(rankings, "rankings");
  }

  @Override public PublicRankingSummary summary(UUID rankingId) {
    if (rankingId == null) throw new RankingEntriesDeniedException();
    var found = rankings.summary(rankingId);
    if (found == null || found.isEmpty() || !rankingId.equals(found.get().rankingId()))
      throw new RankingEntriesDeniedException();
    return found.get();
  }

  @Override public PublicRankingEntriesPage entries(UUID rankingId, UUID after, int limit) {
    if (rankingId == null) throw new RankingEntriesDeniedException();
    if (limit < 1 || limit > MAX_LIMIT) {
      summary(rankingId);
      throw new InvalidPublicRankingPageException();
    }
    var found = rankings.entries(rankingId, after, limit + 1);
    if (found == null || found.isEmpty() || found.get().size() > limit + 1) throw new RankingEntriesDeniedException();
    var rows = found.get();
    UUID cursor = after;
    for (var item : rows) {
      if (item == null || item.entryId() == null || item.total() == null || item.votes() == null
          || item.votes().signum() < 0 || (item.votes().signum() == 0 && item.total().signum() != 0)
          || (cursor != null && cursor.toString().compareTo(item.entryId().toString()) >= 0))
        throw new RankingEntriesDeniedException();
      cursor = item.entryId();
    }
    var items = rows.subList(0, Math.min(limit, rows.size()));
    return new PublicRankingEntriesPage(items, rows.size() > limit ? items.get(items.size()-1).entryId() : null);
  }
}
