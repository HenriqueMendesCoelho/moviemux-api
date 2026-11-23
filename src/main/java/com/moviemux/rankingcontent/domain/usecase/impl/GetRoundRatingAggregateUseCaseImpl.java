package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RoundRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.ReadRoundRatingAggregate;
import com.moviemux.rankingcontent.domain.usecase.GetRoundRatingAggregateUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class GetRoundRatingAggregateUseCaseImpl implements GetRoundRatingAggregateUseCase {
  private final ReadRoundRatingAggregate aggregates;

  public GetRoundRatingAggregateUseCaseImpl(ReadRoundRatingAggregate aggregates) {
    this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
  }

  @Override
  public RoundRatingAggregateView get(RankingActor actor, UUID rank, UUID entry, UUID round) {
    if (actor == null || !actor.accountEnabled() || rank == null || entry == null || round == null)
      throw new RankingEntriesDeniedException();
    var found = aggregates.find(rank, entry, round, actor.userId());
    if (found == null || found.isEmpty()) throw new RankingEntriesDeniedException();
    var view = found.get();
    if (!round.equals(view.roundId()) || !valid(view)) throw new RankingEntriesDeniedException();
    return view;
  }

  private boolean valid(RoundRatingAggregateView view) {
    if (!Boolean.TRUE.equals(view.revealed())) return view.total() == null && view.votes() == null;
    // Signed totals are allowed for legacy outliers. Only counts must be nonnegative.
    return view.total() != null && view.votes() != null && view.votes().signum() >= 0
        && (view.votes().signum() != 0 || view.total().signum() == 0);
  }
}
