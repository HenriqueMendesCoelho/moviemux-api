package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;
import java.util.Optional;
import java.util.UUID;

/** Each write independently revalidates authorization, enablement and recovery under lock. */
public interface ImportTmdbCatalogEntry {
  Optional<AddedCatalogEntry> writeExisting(UUID rank, UUID actor, long tmdbId);

  AddedCatalogEntry writeFetched(UUID rank, UUID actor, TmdbMovieCandidate candidate);
}
