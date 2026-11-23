package com.moviemux.rankingcontent.adapter.integration;

import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.kronusintegrationtool.domain.repository.KronusRankingMovieSearch;
import com.moviemux.rankingcontent.domain.exception.TmdbProviderFailureException;
import com.moviemux.rankingcontent.domain.model.TmdbPickerMovie;
import com.moviemux.rankingcontent.domain.model.TmdbPickerPage;
import com.moviemux.rankingcontent.domain.port.TmdbPickerProvider;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class KitTmdbPickerProvider implements TmdbPickerProvider {
  private final KronusRankingMovieSearch search;

  public KitTmdbPickerProvider(KronusRankingMovieSearch search) { this.search = search; }

  @Override public TmdbPickerPage search(String query, int page, String language) {
    try {
      var result = search.search(query, page, language);
      if (result == null || result.getPage() == null || result.getPage() != page
          || result.getTotalPages() == null || result.getTotalPages() < 0
          || result.getResults() == null || result.getResults().size() > 20
          || (!result.getResults().isEmpty() && result.getTotalPages() < page))
        throw TmdbProviderFailureException.invalidResponse();

      var items = new ArrayList<TmdbPickerMovie>();
      for (var movie : result.getResults()) {
        if (movie == null || movie.getId() == null || movie.getId() < 1 || movie.isAdult())
          throw TmdbProviderFailureException.invalidResponse();
        String original = title(movie.getOriginalTitle());
        String title = title(movie.getTitle());
        if (title == null) title = original;
        if (title == null) throw TmdbProviderFailureException.invalidResponse();
        String poster = movie.getPosterPath();
        if (poster != null && !TmdbPickerMovie.isSafePosterPath(poster)) poster = null;
        items.add(new TmdbPickerMovie(movie.getId(), title, original, movie.getReleaseDate(), poster));
      }
      return new TmdbPickerPage(items, page, !items.isEmpty() && page < 20 && result.getTotalPages() > page);
    } catch (IntegrationFailure failure) {
      var status = failure.getStatus();
      throw switch (failure.getKind()) {
        case REMOTE_STATUS -> status == null ? TmdbProviderFailureException.transport()
            : TmdbProviderFailureException.remoteStatus(status);
        case INVALID_RESPONSE -> TmdbProviderFailureException.invalidResponse();
        case TRANSPORT -> TmdbProviderFailureException.transport();
      };
    }
  }

  private static String title(String value) {
    if (value == null || value.isBlank()) return null;
    if (value.codePointCount(0, value.length()) > 250 || value.codePoints().anyMatch(cp ->
        Character.isISOControl(cp) || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)))
      throw TmdbProviderFailureException.invalidResponse();
    return value;
  }
}
