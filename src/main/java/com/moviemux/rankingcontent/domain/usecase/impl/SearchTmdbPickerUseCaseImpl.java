package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import com.moviemux.rankingcontent.domain.exception.ArchivedCatalogEntryException;
import com.moviemux.rankingcontent.domain.exception.InvalidTmdbSearchException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.exception.TmdbImportUnavailableException;
import com.moviemux.rankingcontent.domain.exception.TmdbProviderFailureException;
import com.moviemux.rankingcontent.domain.model.TmdbPickerMovie;
import com.moviemux.rankingcontent.domain.model.TmdbPickerPage;
import com.moviemux.rankingcontent.domain.port.TmdbPickerProvider;
import com.moviemux.rankingcontent.domain.usecase.ImportTmdbCatalogEntryUseCase;
import com.moviemux.rankingcontent.domain.usecase.SearchTmdbPickerUseCase;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class SearchTmdbPickerUseCaseImpl implements SearchTmdbPickerUseCase {
  private static final int MAX_PAGE = 20;
  private static final int MAX_ITEMS = 20;
  private final CheckRankingCatalogEntryAccess access;
  private final TmdbPickerProvider provider;
  private final BooleanSupplier enabled;

  public SearchTmdbPickerUseCaseImpl(CheckRankingCatalogEntryAccess access, TmdbPickerProvider provider,
      BooleanSupplier enabled) {
    this.access = Objects.requireNonNull(access, "access");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.enabled = Objects.requireNonNull(enabled, "enabled");
  }

  @Override
  public TmdbPickerPage search(RankingActor actor, UUID rank, String query, int page, String language) {
    String normalized = normalize(query);
    if (page < 1 || page > MAX_PAGE || !("pt-BR".equals(language) || "en-US".equals(language)))
      throw new InvalidTmdbSearchException();
    requireAccess(actor, rank);

    TmdbPickerPage result;
    try {
      result = provider.search(normalized, page, language);
    } catch (TmdbProviderFailureException failure) {
      requireAccess(actor, rank);
      throw failure;
    }
    requireAccess(actor, rank);
    return verify(result, page);
  }

  private void requireAccess(RankingActor actor, UUID rank) {
    if (actor == null || !actor.accountEnabled() || rank == null) throw new RankingEntriesDeniedException();
    try {
      if (access.check(actor, rank)) throw new ArchivedCatalogEntryException();
    } catch (RankingAccessDeniedException denied) {
      throw new RankingEntriesDeniedException();
    }
    if (!enabled.getAsBoolean()) throw new TmdbImportUnavailableException();
  }

  private static TmdbPickerPage verify(TmdbPickerPage page, int requested) {
    if (page == null || page.items() == null || page.page() != requested || page.items().size() > MAX_ITEMS)
      throw TmdbProviderFailureException.invalidResponse();
    for (var item : page.items()) {
      if (item.tmdbId() < 1 || item.tmdbId() > ImportTmdbCatalogEntryUseCase.MAX_TMDB_ID
          || !title(item.title()) || (item.originalTitle() != null && !title(item.originalTitle()))
          || (item.posterPath() != null && !TmdbPickerMovie.isSafePosterPath(item.posterPath())))
        throw TmdbProviderFailureException.invalidResponse();
    }
    return new TmdbPickerPage(page.items(), requested, page.hasMore() && requested < MAX_PAGE && !page.items().isEmpty());
  }

  private static String normalize(String query) {
    if (query == null) throw new InvalidTmdbSearchException();
    int start = 0, end = query.length();
    while (start < end && whitespace(query.codePointAt(start))) start += Character.charCount(query.codePointAt(start));
    while (end > start && whitespace(query.codePointBefore(end))) end -= Character.charCount(query.codePointBefore(end));
    String trimmed = query.substring(start, end);
    int length = trimmed.codePointCount(0, trimmed.length());
    if (length < 2 || length > 120 || !nonblankText(trimmed)) throw new InvalidTmdbSearchException();
    return trimmed;
  }

  private static boolean whitespace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }

  private static boolean title(String text) {
    return nonblankText(text) && text.codePointCount(0, text.length()) <= 250;
  }

  private static boolean nonblankText(String text) {
    return text != null && text.codePoints().anyMatch(cp -> !whitespace(cp))
        && text.codePoints().noneMatch(cp -> Character.isISOControl(cp)
            || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE));
  }
}
