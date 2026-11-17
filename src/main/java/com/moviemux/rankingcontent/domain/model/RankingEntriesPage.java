package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.UUID;

public record RankingEntriesPage(List<RankingMovieEntryView> items, UUID nextCursor) {
  public RankingEntriesPage { items=List.copyOf(items); }
}
