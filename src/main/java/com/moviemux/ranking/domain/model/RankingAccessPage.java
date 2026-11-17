package com.moviemux.ranking.domain.model;

import java.util.List;
import java.util.UUID;

/** No total count or metadata about rankings outside the caller's current access. */
public record RankingAccessPage(List<RankingAccessView> items, UUID nextCursor) {
  public RankingAccessPage { items = List.copyOf(items); }
}
