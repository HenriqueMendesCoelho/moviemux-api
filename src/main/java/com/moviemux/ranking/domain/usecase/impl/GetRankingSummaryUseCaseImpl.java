package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RankingSummary;
import com.moviemux.ranking.domain.port.CurrentRankingSummary;
import com.moviemux.ranking.domain.usecase.GetRankingSummaryUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class GetRankingSummaryUseCaseImpl implements GetRankingSummaryUseCase {
  private final CurrentRankingSummary summaries;

  public GetRankingSummaryUseCaseImpl(CurrentRankingSummary summaries) {
    this.summaries = Objects.requireNonNull(summaries, "summaries");
  }

  @Override
  public RankingSummary get(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingAccessDeniedException();
    var result = summaries.find(actor.userId(), rankingId);
    if (result == null || result.isEmpty()) throw new RankingAccessDeniedException();
    var summary = result.get();
    if (!rankingId.equals(summary.rankingId()) || summary.visibility() == null || summary.admission() == null
        || summary.capabilities() == null || !summary.capabilities().contains(RankingCapability.READ))
      throw new RankingAccessDeniedException();
    return summary;
  }
}
