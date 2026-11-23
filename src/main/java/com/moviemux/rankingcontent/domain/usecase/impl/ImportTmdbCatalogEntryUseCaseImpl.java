package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;
import com.moviemux.rankingcontent.domain.port.ImportTmdbCatalogEntry;
import com.moviemux.rankingcontent.domain.port.TmdbMovieDetailsProvider;
import com.moviemux.rankingcontent.domain.usecase.ImportTmdbCatalogEntryUseCase;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class ImportTmdbCatalogEntryUseCaseImpl implements ImportTmdbCatalogEntryUseCase {
  private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]{1,253}");
  private final CheckRankingCatalogEntryAccess access;
  private final ImportTmdbCatalogEntry writes;
  private final TmdbMovieDetailsProvider provider;
  private final boolean enabled;

  public ImportTmdbCatalogEntryUseCaseImpl(CheckRankingCatalogEntryAccess access, ImportTmdbCatalogEntry writes,
      TmdbMovieDetailsProvider provider, boolean enabled) {
    this.access = Objects.requireNonNull(access, "access");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.enabled = enabled;
  }

  @Override
  public AddedCatalogEntry execute(RankingActor actor, UUID rankingId, long tmdbId) {
    if (tmdbId < 1 || tmdbId > MAX_TMDB_ID) throw new InvalidTmdbIdException();
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingEntriesDeniedException();
    try {
      if (access.check(actor, rankingId)) throw new ArchivedCatalogEntryException();
    } catch (RankingAccessDeniedException denied) {
      throw new RankingEntriesDeniedException();
    }
    if (!enabled) throw new TmdbImportUnavailableException();

    // No enclosing transaction: each write owns its short transaction; provider runs between them.
    var existing = writes.writeExisting(rankingId, actor.userId(), tmdbId);
    if (existing == null) throw new RankingEntriesDeniedException();
    if (existing.isPresent()) return existing.get();
    var candidate = provider.fetch(tmdbId);
    validate(tmdbId, candidate);
    var added = writes.writeFetched(rankingId, actor.userId(), candidate);
    if (added == null) throw new RankingEntriesDeniedException();
    return added;
  }

  private void validate(long tmdbId, TmdbMovieCandidate candidate) {
    if (candidate == null || candidate.tmdbId() != tmdbId
        || (candidate.imdbId() != null && !IMDB_ID.matcher(candidate.imdbId()).matches())
        || !text(candidate.portugueseTitle(), 150, false) || !text(candidate.englishTitle(), 150, false)
        || !text(candidate.originalTitle(), 150, false) || !text(candidate.director(), 150, false)
        || !text(candidate.description(), Integer.MAX_VALUE, true)
        || !url(candidate.urlImage()) || !url(candidate.portugueseUrlTrailer()) || !url(candidate.englishUrlTrailer())
        || (candidate.runtime() != null && candidate.runtime() < 0)
        || (candidate.releaseDate() != null && (candidate.releaseDate().getYear() < -4712
            || candidate.releaseDate().getYear() > 294276))) throw TmdbProviderFailureException.invalidResponse();
  }

  private boolean text(String value, int max, boolean multiline) {
    if (value == null) return true;
    if (value.isBlank() || value.codePointCount(0, value.length()) > max) return false;
    return value.codePoints().noneMatch(cp -> (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)
        || (Character.isISOControl(cp) && !(multiline && (cp == '\n' || cp == '\r' || cp == '\t'))));
  }

  private boolean url(String value) {
    if (value == null) return true;
    try {
      var uri = new URI(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null && uri.getUserInfo() == null;
    } catch (URISyntaxException malformed) {
      return false;
    }
  }
}
