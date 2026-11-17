package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ListCurrentRankingAccess;
import com.moviemux.ranking.domain.usecase.ListRankingAccessUseCase;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ListRankingAccessUseCaseImpl implements ListRankingAccessUseCase {
  private final ListCurrentRankingAccess access;

  public ListRankingAccessUseCaseImpl(ListCurrentRankingAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  @Override public RankingAccessPage execute(RankingActor actor, UUID after, int limit) {
    if (actor == null || !actor.accountEnabled()) throw new RankingAccessDeniedException();
    if (limit < 1 || limit > 100) throw new InvalidRankingAccessPageException();
    var snapshots = access.findAccessible(actor.userId(), after, limit + 1);
    if (snapshots == null || snapshots.size() > limit + 1) throw new RankingAccessDeniedException();
    var items = new ArrayList<RankingAccessView>();
    UUID previous = after;
    // Validate every row, including lookahead, before publishing any data or cursor.
    for (var snapshot : snapshots) {
      if (snapshot == null) throw new RankingAccessDeniedException();
      var ranking = snapshot.ranking();
      var member = snapshot.membership().orElseThrow(RankingAccessDeniedException::new);
      boolean owner = actor.userId().equals(ranking.ownerUserId());
      if (!member.active() || !actor.userId().equals(member.userId())
          || !ranking.id().equals(member.rankingId())
          || (!owner && !member.capabilities().contains(RankingCapability.READ))
          // UUID.compareTo is signed; PostgreSQL UUID ordering is unsigned byte ordering.
          || (previous != null && previous.toString().compareTo(ranking.id().toString()) >= 0)) {
        throw new RankingAccessDeniedException();
      }
      previous = ranking.id();
      if (items.size() < limit) items.add(new RankingAccessView(ranking.id(), owner
          ? EnumSet.allOf(RankingCapability.class) : member.capabilities()));
    }
    UUID cursor = snapshots.size() > limit ? items.get(items.size() - 1).rankingId() : null;
    return new RankingAccessPage(items, cursor);
  }
}
