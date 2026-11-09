package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.repository.MovieGenreRepository;
import com.moviemux.movie.domain.usecase.CreateMovieGenreUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMovieGenreUseCaseImpl implements CreateMovieGenreUseCase {

  private final KronusCatalogRepository kronusIntegrationToolRepository;

  private final MovieGenreRepository repository;

  @Override
  public void execute() {
    List<MovieGenre> genres =
        kronusIntegrationToolRepository.listGenres().stream()
            .map(
                genre ->
                    MovieGenre.builder()
                        .id(genre.id())
                        .tmdbId(genre.id())
                        .name(genre.name())
                        .build())
            .toList();

    log.info("Started integration with TMDB of movie genres");

    int count = 0;

    for (MovieGenre genre : genres) {
      MovieGenre exists = repository.findByName(genre.getName());

      if (exists != null) {
        continue;
      }
      count++;
      repository.save(genre);
    }

    log.info(
        String.format(
            "Completed integration with TMDB of film genres, %s new genres registered", count));
  }
}
