package com.moviemux.movie.domain.model;
import java.util.UUID;
/** Public catalog projection for other capabilities. */
public record MovieCatalogEntry(UUID id, Long tmdbId, String portugueseTitle) { }
