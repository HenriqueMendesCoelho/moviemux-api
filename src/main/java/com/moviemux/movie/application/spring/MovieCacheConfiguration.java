package com.moviemux.movie.application.spring;

import com.moviemux.movie.domain.usecase.*;
import com.moviemux.movie.domain.usecase.impl.*;
import org.springframework.context.annotation.*;

@Configuration
public class MovieCacheConfiguration {
  @Bean
  @Primary
  public CreateMovieUseCase cachedCreateMovieUseCase(
      CreateMovieUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      var result = delegate.execute(command);
      cache.invalidate();
      return result;
    };
  }

  @Bean
  @Primary
  public UpdateMovieUseCase cachedUpdateMovieUseCase(
      UpdateMovieUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      var result = delegate.execute(command);
      cache.invalidate();
      return result;
    };
  }

  @Bean
  @Primary
  public DeleteMovieUseCase cachedDeleteMovieUseCase(
      DeleteMovieUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      delegate.execute(command);
      cache.invalidate();
    };
  }

  @Bean
  @Primary
  public CreateMovieNoteUseCase cachedCreateMovieNoteUseCase(
      CreateMovieNoteUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      var result = delegate.execute(command);
      cache.invalidate();
      return result;
    };
  }

  @Bean
  @Primary
  public UpdateMovieNoteUseCase cachedUpdateMovieNoteUseCase(
      UpdateMovieNoteUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      var result = delegate.execute(command);
      cache.invalidate();
      return result;
    };
  }

  @Bean
  @Primary
  public DeleteMovieNoteUseCase cachedDeleteMovieNoteUseCase(
      DeleteMovieNoteUseCaseImpl delegate, MovieStatisticsCache cache) {
    return command -> {
      delegate.execute(command);
      cache.invalidate();
    };
  }
}
