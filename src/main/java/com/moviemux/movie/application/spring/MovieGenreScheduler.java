package com.moviemux.movie.application.spring;
import com.moviemux.movie.domain.usecase.CreateMovieGenreUseCase;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class MovieGenreScheduler {
 private final CreateMovieGenreUseCase sync;
 public MovieGenreScheduler(CreateMovieGenreUseCase sync){this.sync=sync;}
 @Scheduled(initialDelay=5000,fixedDelayString="P15D")
 public void synchronize(){sync.execute();}
}
