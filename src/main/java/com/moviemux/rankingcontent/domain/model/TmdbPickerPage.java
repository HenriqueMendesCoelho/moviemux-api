package com.moviemux.rankingcontent.domain.model;

import java.util.List;

public record TmdbPickerPage(List<TmdbPickerMovie> items, int page, boolean hasMore) {
  public TmdbPickerPage {
    if (items != null) items = List.copyOf(items);
  }
}
