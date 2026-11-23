package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.LegacyFilmLink;
import java.util.Optional;
import java.util.UUID;

/** Reads only the exact authorized live mapping, including current phase, in a single snapshot. */
public interface ReadLegacyFilmLink {
  Optional<LegacyFilmLink> find(UUID actorId, UUID legacyMovieId);
}
