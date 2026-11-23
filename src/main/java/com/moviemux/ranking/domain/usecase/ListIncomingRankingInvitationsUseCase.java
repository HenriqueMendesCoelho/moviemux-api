package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.IncomingRankingInvitationPage;
import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface ListIncomingRankingInvitationsUseCase {
  IncomingRankingInvitationPage list(RankingActor actor, UUID after, int limit);
}
