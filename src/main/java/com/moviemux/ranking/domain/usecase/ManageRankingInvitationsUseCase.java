package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingInvitationReceipt;
import java.util.UUID;

public interface ManageRankingInvitationsUseCase {
  RankingInvitationReceipt issue(RankingActor actor, UUID rankingId, UUID invitee);

  void accept(RankingActor actor, UUID rankingId, UUID inviteId);

  void revoke(RankingActor actor, UUID rankingId, UUID inviteId);
}
