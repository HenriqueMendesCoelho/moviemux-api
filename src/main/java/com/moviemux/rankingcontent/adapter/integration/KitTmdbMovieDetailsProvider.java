package com.moviemux.rankingcontent.adapter.integration;

import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.kronusintegrationtool.domain.usecase.MovieSumaryUseCase;
import com.moviemux.rankingcontent.domain.exception.TmdbProviderFailureException;
import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;
import com.moviemux.rankingcontent.domain.port.TmdbMovieDetailsProvider;
import org.springframework.stereotype.Component;

@Component
public class KitTmdbMovieDetailsProvider implements TmdbMovieDetailsProvider {
  private final MovieSumaryUseCase summary;

  public KitTmdbMovieDetailsProvider(MovieSumaryUseCase summary) { this.summary = summary; }

  @Override public TmdbMovieCandidate fetch(long tmdbId) {
    try {
      var value = summary.execute(tmdbId);
      if (value == null || value.getTmdbId() == null) throw TmdbProviderFailureException.invalidResponse();
      // The historical KIT adapter puts its PT image in the English field. Reuse
      // that verified image as unlocalized; do not introduce a new legacy mapping.
      return new TmdbMovieCandidate(value.getTmdbId(), value.getImdbId(), value.getPortugueseTitle(),
          value.getEnglishTitle(), value.getOriginalTitle(), value.getDirector(), value.getUrlImageEnglish(),
          value.getPortugueseUrlTrailer(), value.getEnglishUrlTrailer(), value.getDescription(),
          value.getReleaseDate(), value.getRuntime());
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
}
