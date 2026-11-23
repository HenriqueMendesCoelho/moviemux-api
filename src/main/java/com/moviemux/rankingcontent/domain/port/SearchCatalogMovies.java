package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.CatalogMovieSearchPage.Item;
import java.util.List;
import java.util.UUID;

/** Must evaluate current scoped ADD_ENTRY/owner authority and the catalog page in one read. */
public interface SearchCatalogMovies {
  List<Item> find(UUID rankingId, UUID actorId, String query, String language, int offset, int maxRows);
}
