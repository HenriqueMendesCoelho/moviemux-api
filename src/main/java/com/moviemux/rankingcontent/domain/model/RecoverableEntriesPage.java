package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.UUID;

public record RecoverableEntriesPage(List<RecoverableEntry> items, UUID nextCursor) {
  public RecoverableEntriesPage { items = List.copyOf(items); }
}
