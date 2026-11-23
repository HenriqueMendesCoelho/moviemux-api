package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.InvalidVisibilitySettingsException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.RankingVisibilitySettings;
import com.moviemux.ranking.domain.usecase.ManageRankingVisibilityUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageRankingVisibilityUseCaseImpl implements ManageRankingVisibilityUseCase {
  private final RankingVisibilitySettings settings;

  public ManageRankingVisibilityUseCaseImpl(RankingVisibilitySettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public RankingVisibility current(RankingActor actor, UUID rankingId) {
    var current = settings.current(rankingId, actorId(actor, rankingId));
    if (current == null) throw new RankingAccessDeniedException();
    return current;
  }

  @Override
  public RankingVisibility set(RankingActor actor, UUID rankingId, RankingVisibility visibility) {
    UUID account = actorId(actor, rankingId);
    if (visibility == null) throw new InvalidVisibilitySettingsException();
    var committed = settings.set(rankingId, account, visibility);
    if (committed != visibility) throw new RankingAccessDeniedException();
    return committed;
  }

  private UUID actorId(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingAccessDeniedException();
    return actor.userId();
  }
}
