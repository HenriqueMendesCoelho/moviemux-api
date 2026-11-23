package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.UUID;

public record PublicRankingEntriesPage(List<PublicRankingEntry> items, UUID nextCursor) {
  public PublicRankingEntriesPage { items = List.copyOf(items); }
}
