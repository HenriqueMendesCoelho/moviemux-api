package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.application.spring.bridge.UserActivityBridge;
import com.moviemux.movie.domain.repository.MovieActivityReader;
import com.moviemux.movie.domain.repository.MovieActivityRepository;
import com.moviemux.movie.domain.repository.MovieActivityRepository.Facts;
import com.moviemux.movie.domain.repository.MovieActivityRepository.Review;
import com.moviemux.movie.domain.usecase.ReadMovieActivityUseCase;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivityContractTest {
  private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final MovieActivityRepository repository = mock(MovieActivityRepository.class);
  private final ReadMovieActivityUseCase useCase = new ReadMovieActivityUseCase(repository);

  @Test
  void computesProfileActivityWithoutPersistenceObjects() {
    when(repository.read(id))
        .thenReturn(new Facts(List.of(new Review(8, 91), new Review(9, 92)), List.of(id)));
    assertThat(useCase.read(id)).isEqualTo(new MovieActivityReader.Activity(2, 1, 183, 8.5));
  }

  @Test
  void emptyActivityRemainsZero() {
    when(repository.read(id)).thenReturn(new Facts(List.of(), List.of()));
    assertThat(useCase.read(id)).isEqualTo(new MovieActivityReader.Activity(0, 0, 0, 0));
  }

  @Test
  void nullRatingIsNotSilentlyDiscarded() {
    when(repository.read(id)).thenReturn(new Facts(List.of(new Review(null, 90)), List.of()));
    assertThatThrownBy(() -> useCase.read(id)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullRuntimeIsNotSilentlyZeroed() {
    when(repository.read(id)).thenReturn(new Facts(List.of(new Review(8, null)), List.of()));
    assertThatThrownBy(() -> useCase.read(id)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void profileWithoutStatisticsDoesNotQueryMovieData() {
    UserRepository users = mock(UserRepository.class);
    MovieActivityReader movies = mock(MovieActivityReader.class);
    when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).build()));
    var result = new UserActivityBridge(users, movies).read(id);
    assertThat(result.ratingsGiven()).isZero();
    assertThat(result.registeredMovies()).isZero();
    assertThat(result.displayTime()).isZero();
    assertThat(result.averageRatingMovies()).isZero();
    verifyNoInteractions(movies);
  }
}
