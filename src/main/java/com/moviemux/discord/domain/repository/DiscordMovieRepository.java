package com.moviemux.discord.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface DiscordMovieRepository {
  Optional<MovieReferenceTarget> find(UUID movieId);
}
