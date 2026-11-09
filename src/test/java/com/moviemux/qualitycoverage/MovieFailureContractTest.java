package com.moviemux.qualitycoverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.moviemux.movie.domain.exception.DuplicatedMovieException;
import com.moviemux.movie.domain.exception.DuplicatedMovieNoteException;
import org.junit.jupiter.api.Test;

class MovieFailureContractTest {
  @Test
  void duplicateMovieExposesDefaultAndCallerProvidedMessages() {
    assertThat(new DuplicatedMovieException()).hasMessage("Movie duplicated").hasNoCause();
    assertThat(new DuplicatedMovieException("Movie already registered"))
        .hasMessage("Movie already registered")
        .hasNoCause();
  }

  @Test
  void duplicateRatingExposesDefaultAndCallerProvidedMessages() {
    assertThat(new DuplicatedMovieNoteException()).hasMessage("Movie note duplicated").hasNoCause();
    assertThat(new DuplicatedMovieNoteException("Rating already registered"))
        .hasMessage("Rating already registered")
        .hasNoCause();
  }
}
