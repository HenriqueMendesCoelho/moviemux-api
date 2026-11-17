package com.moviemux.rankingcontent.domain.model;
import java.util.List;
public record RankingMovieRoundListPage(List<RankingMovieRoundListItem> items, Long nextCursor) {
  public RankingMovieRoundListPage { items = List.copyOf(items); }
}
