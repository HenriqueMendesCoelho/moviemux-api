package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;

/** Bounded, non-transactional request for typed and verified film metadata only. */
public interface TmdbMovieDetailsProvider {
  TmdbMovieCandidate fetch(long tmdbId);
}
