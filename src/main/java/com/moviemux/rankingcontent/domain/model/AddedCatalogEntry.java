package com.moviemux.rankingcontent.domain.model;

import java.util.Objects;
import java.util.UUID;

public record AddedCatalogEntry(UUID entryId, boolean created) {
  public AddedCatalogEntry { Objects.requireNonNull(entryId); }
}
