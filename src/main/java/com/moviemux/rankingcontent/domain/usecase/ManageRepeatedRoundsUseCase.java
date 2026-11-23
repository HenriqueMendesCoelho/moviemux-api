package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import java.util.UUID;

public interface ManageRepeatedRoundsUseCase {
  boolean settings(RankingActor actor, UUID rankingId);

  boolean setSettings(RankingActor actor, UUID rankingId, boolean enabled);

  RepeatedRoundReceipt create(RankingActor actor, UUID rankingId, UUID entryId, UUID commandId, String description);
}
