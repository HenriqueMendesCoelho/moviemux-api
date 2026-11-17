package com.moviemux.ranking.domain.service;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.port.CurrentRankingEntryRemovalTarget;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authorization policy only, not a removal endpoint or mutation implementation. */
public final class RankingEntryRemovalAuthorization {
  private final RankingAuthorization authorization;
  private final CurrentRankingEntryRemovalTarget entries;
  public RankingEntryRemovalAuthorization(RankingAuthorization authorization, CurrentRankingEntryRemovalTarget entries) {
    this.authorization=Objects.requireNonNull(authorization);this.entries=Objects.requireNonNull(entries);
  }
  public void require(RankingActor actor, UUID rankingId, UUID entryId) {
    if(actor==null || !actor.accountEnabled() || rankingId==null || entryId==null)throw new RankingAccessDeniedException();
    var result=entries.find(rankingId,entryId);
    if(result==null)throw new RankingAccessDeniedException();
    var target=result.orElseThrow(RankingAccessDeniedException::new);
    if(!entryId.equals(target.entryId()) || !rankingId.equals(target.rankingId()))throw new RankingAccessDeniedException();
    boolean own=target.creatorAccountId().filter(actor.userId()::equals).isPresent();
    authorization.authorizeAny(actor,rankingId,own
        ? Set.of(RankingCapability.REMOVE_OWN_ENTRY,RankingCapability.REMOVE_ENTRY)
        : Set.of(RankingCapability.REMOVE_ENTRY));
  }
}
