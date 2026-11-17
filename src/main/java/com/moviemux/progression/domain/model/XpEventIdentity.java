package com.moviemux.progression.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Server-assigned source identity. Policy versions are deliberately not part of deduplication. */
public record XpEventIdentity(String source, UUID eventId) {
  public XpEventIdentity {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(eventId, "eventId");
    if (source.isBlank() || !source.equals(source.strip())) {
      throw new IllegalArgumentException("Event source must be nonblank without surrounding whitespace");
    }
  }
}
