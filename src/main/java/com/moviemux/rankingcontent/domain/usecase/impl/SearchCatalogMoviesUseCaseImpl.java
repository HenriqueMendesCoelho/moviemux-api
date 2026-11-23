package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidCatalogMovieSearchException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.CatalogMovieSearchPage;
import com.moviemux.rankingcontent.domain.port.SearchCatalogMovies;
import com.moviemux.rankingcontent.domain.usecase.SearchCatalogMoviesUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SearchCatalogMoviesUseCaseImpl implements SearchCatalogMoviesUseCase {
  private static final int PAGE_SIZE = 20;
  private static final int MAX_PAGE = 20;
  private final SearchCatalogMovies catalog;

  public SearchCatalogMoviesUseCaseImpl(SearchCatalogMovies catalog) { this.catalog = Objects.requireNonNull(catalog); }

  @Override
  public CatalogMovieSearchPage search(RankingActor actor, UUID rank, String query, int page, String language) {
    String normalized = normalize(query);
    if (page < 1 || page > MAX_PAGE || !("pt-BR".equals(language) || "en-US".equals(language)))
      throw new InvalidCatalogMovieSearchException();
    if (actor == null || !actor.accountEnabled() || rank == null) throw new RankingEntriesDeniedException();
    int maxRows = PAGE_SIZE + (page < MAX_PAGE ? 1 : 0);
    var rows = catalog.find(rank, actor.userId(), normalized, language, (page - 1) * PAGE_SIZE, maxRows);
    if (rows.size() > maxRows) throw new IllegalStateException("Catalog movie search exceeded page bound");
    return new CatalogMovieSearchPage(rows.subList(0, Math.min(rows.size(), PAGE_SIZE)), page, rows.size() > PAGE_SIZE);
  }

  private static String normalize(String query) {
    if (query == null) throw new InvalidCatalogMovieSearchException();
    int start = 0, end = query.length();
    while (start < end && whitespace(query.codePointAt(start))) start += Character.charCount(query.codePointAt(start));
    while (end > start && whitespace(query.codePointBefore(end))) end -= Character.charCount(query.codePointBefore(end));
    String result = query.substring(start, end);
    int length = result.codePointCount(0, result.length());
    if (length < 2 || length > 120 || result.codePoints().anyMatch(cp -> Character.isISOControl(cp)
        || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))) throw new InvalidCatalogMovieSearchException();
    return result;
  }

  private static boolean whitespace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
}
