package com.moviemux.rankingcontent.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Authoritative local movie-catalog binding; never constructed from untrusted request metadata. */
public record CatalogMovieRound(UUID catalogMovieId, RoundIdentity round) {
  public CatalogMovieRound {
    Objects.requireNonNull(catalogMovieId, "catalogMovieId");
    Objects.requireNonNull(round, "round");
  }
}
