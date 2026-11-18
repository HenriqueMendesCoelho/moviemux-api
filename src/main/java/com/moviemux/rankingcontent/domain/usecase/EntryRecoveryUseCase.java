package com.moviemux.rankingcontent.domain.usecase;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import java.util.UUID;
public interface EntryRecoveryUseCase {
  EntryRecoveryState preview(RankingActor actor,UUID rankingId,UUID entryId);
  EntryRecoveryState remove(RankingActor actor,UUID rankingId,UUID entryId,Long expectedVersion,boolean confirmed);
  EntryRecoveryState restore(RankingActor actor,UUID rankingId,UUID entryId,UUID removalToken);
}
