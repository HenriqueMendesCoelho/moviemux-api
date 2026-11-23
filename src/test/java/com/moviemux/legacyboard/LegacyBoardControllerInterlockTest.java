package com.moviemux.legacyboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.discord.adapter.controller.impl.DiscordControllerImpl;
import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.domain.usecase.UpdateMessageWebhookUseCase;
import com.moviemux.legacyboard.domain.exception.LegacyBoardRetiredException;
import com.moviemux.legacyboard.domain.exception.LegacyBoardUnavailableException;
import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import com.moviemux.movie.adapter.controller.impl.MovieControllerImpl;
import com.moviemux.movie.domain.usecase.*;
import com.moviemux.statistic.adapter.controller.impl.StatisticControllerImpl;
import com.moviemux.statistic.domain.usecase.MovieStatisticsUseCase;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

class LegacyBoardControllerInterlockTest {
  private static final UUID MOVIE = new UUID(0,1);
  private final LegacyBoardInterlock gate = mock(LegacyBoardInterlock.class);
  private final CreateMovieUseCase createMovie = mock(CreateMovieUseCase.class);
  private final SearchMovieUseCase searchMovie = mock(SearchMovieUseCase.class);
  private final GetMovieUseCase getMovie = mock(GetMovieUseCase.class);
  private final SearchMovieGenresWithMoviesUseCase withMovies = mock(SearchMovieGenresWithMoviesUseCase.class);
  private final UpdateMovieUseCase updateMovie = mock(UpdateMovieUseCase.class);
  private final DeleteMovieUseCase deleteMovie = mock(DeleteMovieUseCase.class);
  private final CreateMovieNoteUseCase createNote = mock(CreateMovieNoteUseCase.class);
  private final SearchMovieNoteUseCase searchNote = mock(SearchMovieNoteUseCase.class);
  private final UpdateMovieNoteUseCase updateNote = mock(UpdateMovieNoteUseCase.class);
  private final DeleteMovieNoteUseCase deleteNote = mock(DeleteMovieNoteUseCase.class);
  private final SearchMovieGenreUseCase genres = mock(SearchMovieGenreUseCase.class);
  private final SendMessageWebhookUseCase send = mock(SendMessageWebhookUseCase.class);
  private final UpdateMessageWebhookUseCase edit = mock(UpdateMessageWebhookUseCase.class);
  private final MovieStatisticsUseCase charts = mock(MovieStatisticsUseCase.class);
  private final MovieControllerImpl movies = new MovieControllerImpl(gate, createMovie, searchMovie, getMovie,
      withMovies, updateMovie, deleteMovie, createNote, searchNote, updateNote, deleteNote, genres);
  private final DiscordControllerImpl discord = new DiscordControllerImpl(send, edit, gate);
  private final StatisticControllerImpl statistics = new StatisticControllerImpl(gate, charts);

  @Test void everyBoardCallStopsBeforeQueryCacheDispatchAndDtoAccess() {
    var actions = List.<ThrowingCallable>of(
        () -> movies.listAllMovies(null,null,null,Pageable.unpaged(),null),
        () -> movies.getById(MOVIE,null), () -> movies.save(null,null),
        () -> movies.update(null,MOVIE,null), () -> movies.delete(MOVIE,null),
        () -> movies.listMovieNotes(MOVIE,null), () -> movies.createMovieNote(null,null),
        () -> movies.updateMovieNote(MOVIE,null,null), () -> movies.deleteMovieNote(MOVIE,null),
        movies::listAllGenresWithMovies, statistics::charts, () -> discord.execute(MOVIE), () -> discord.update(MOVIE));
    for(var failure : List.of(new LegacyBoardRetiredException(),new LegacyBoardUnavailableException())){
      doThrow(failure).when(gate).requireLegacy();
      for(var action : actions) assertThatThrownBy(action).isSameAs(failure);
    }
    verify(gate,times(26)).requireLegacy();
    verifyNoInteractions(createMovie,searchMovie,getMovie,withMovies,updateMovie,deleteMovie,createNote,
        searchNote,updateNote,deleteNote,genres,send,edit,charts);
  }

  @Test void genreReferenceRemainsIndependentAndLegacyDiscordIsGuardedBeforeDispatch() {
    doThrow(new LegacyBoardRetiredException()).doNothing().when(gate).requireLegacy();
    when(genres.execute()).thenReturn(List.of());
    assertThat(movies.listGenres()).isEmpty();
    verifyNoInteractions(gate);
    assertThatThrownBy(()->discord.execute(MOVIE)).isInstanceOf(LegacyBoardRetiredException.class);
    discord.update(MOVIE);
    var order = inOrder(gate,edit);
    order.verify(gate,times(2)).requireLegacy();
    order.verify(edit).execute(MOVIE);
    verifyNoInteractions(send);
  }

  @Test void onlySelectedMethodsOwnActualWriteCapableTransactions() {
    for(var owner : List.of(MovieControllerImpl.class,StatisticControllerImpl.class,DiscordControllerImpl.class)) {
      for(var method : owner.getDeclaredMethods()) {
        if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
        var tx = method.getAnnotation(Transactional.class);
        if (method.getName().equals("listGenres")) assertThat(tx).isNull();
        else {
          assertThat(tx).as(method.toString()).isNotNull();
          assertThat(tx.readOnly()).isFalse();
        }
      }
    }
  }
}
