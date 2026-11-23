package com.moviemux.ranking.domain.model;

import java.util.List;
import java.util.UUID;

public record RankingSummaryPage(List<RankingSummary> items, UUID nextCursor) {
  public RankingSummaryPage { items = List.copyOf(items); }
}
