package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.TmdbPickerPage;

public interface TmdbPickerProvider {
  /** Bounded external lookup. Never disclose actor/ranking identity and surface only typed failures. */
  TmdbPickerPage search(String query, int page, String language);
}
