package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.service.RankingAuthorization;
import com.moviemux.ranking.domain.usecase.GetRankingAccessUseCase;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GetRankingAccessUseCaseImpl implements GetRankingAccessUseCase {
  private final RankingAuthorization authorization;

  public GetRankingAccessUseCaseImpl(RankingAuthorization authorization) {
    this.authorization = Objects.requireNonNull(authorization, "authorization");
  }

  @Override
  public RankingAccessView execute(RankingActor actor, UUID rankingId) {
    var access = authorization.authorize(actor, rankingId, RankingCapability.READ);
    var grants = access.ranking().archived() ? EnumSet.of(RankingCapability.READ)
        : actor.userId().equals(access.ranking().ownerUserId())
        ? EnumSet.allOf(RankingCapability.class) : access.membership().orElseThrow().capabilities();
    return new RankingAccessView(rankingId, grants);
  }
}
