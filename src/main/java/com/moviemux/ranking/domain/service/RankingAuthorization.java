package com.moviemux.ranking.domain.service;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RankingIdentity;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.CurrentRankingAccess;
import java.util.Objects;
import java.util.UUID;

/** Scope eligibility only: rating windows and response disclosure require their own policies. */
public final class RankingAuthorization {
  private final CurrentRankingAccess access;

  public RankingAuthorization(CurrentRankingAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  public void require(RankingActor actor, UUID rankingId, RankingCapability capability) {
    if (actor == null || !actor.accountEnabled() || rankingId == null || capability == null) {
      throw new RankingAccessDeniedException();
    }
    var snapshot = access.find(rankingId, actor.userId())
        .orElseThrow(RankingAccessDeniedException::new);
    if (!rankingId.equals(snapshot.ranking().id())) throw new RankingAccessDeniedException();
    var member = snapshot.membership().orElseThrow(RankingAccessDeniedException::new);
    if (!member.active() || !actor.userId().equals(member.userId())) {
      throw new RankingAccessDeniedException();
    }
    if (!actor.userId().equals(snapshot.ranking().ownerUserId())
        && !member.capabilities().contains(capability)) {
      throw new RankingAccessDeniedException();
    }
  }

  /** Resource scope must come from authoritative persistence, not another request parameter. */
  public void requireResource(RankingActor actor, UUID rankingId, UUID resourceRankingId,
      RankingCapability capability) {
    if (rankingId == null || !rankingId.equals(resourceRankingId)) {
      throw new RankingAccessDeniedException();
    }
    require(actor, rankingId, capability);
  }

  /** Only permits use of a separately approved public DTO, never a member/private response. */
  public static boolean allowsPublicProjection(RankingIdentity ranking) {
    return ranking != null && ranking.visibility() == RankingVisibility.PUBLIC;
  }
}
