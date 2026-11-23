package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import java.util.Optional;

public interface ManageRankingInvitationAddressUseCase {
  Optional<String> get(RankingActor actor);

  String rotate(RankingActor actor);
}
