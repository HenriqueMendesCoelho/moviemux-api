package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.JoinOpenRanking;
import com.moviemux.ranking.domain.usecase.JoinOpenRankingUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class JoinOpenRankingUseCaseImpl implements JoinOpenRankingUseCase {
  private final JoinOpenRanking rankings;

  public JoinOpenRankingUseCaseImpl(JoinOpenRanking rankings) {
    this.rankings = Objects.requireNonNull(rankings, "rankings");
  }

  @Override
  public void join(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null
        || !rankings.join(rankingId, actor.userId())) throw new RankingAccessDeniedException();
  }
}
