package com.moviemux.legacyboard;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moviemux.application.spring.handler.SpringControllerAdviceHandler;
import com.moviemux.discord.adapter.controller.DiscordController;
import com.moviemux.discord.application.spring.controller.DiscordRestrictedSpringController;
import com.moviemux.legacyboard.application.spring.LegacyBoardErrorAdvice;
import com.moviemux.legacyboard.domain.exception.LegacyBoardRetiredException;
import com.moviemux.legacyboard.domain.exception.LegacyBoardUnavailableException;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.application.spring.controller.MovieSpringController;
import com.moviemux.statistic.adapter.controller.StatisticController;
import com.moviemux.statistic.application.spring.controller.StatisticSpringController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

class LegacyBoardHttpEnvelopeTest {
  @Test void exactResponsesAreIdenticalAcrossBoardOperationsAndGenresRemainsIndependent() throws Exception {
    UUID movie = new UUID(0,2);
    var movies = mock(MovieController.class);
    var charts = mock(StatisticController.class);
    var discord = mock(DiscordController.class);
    when(movies.listAllGenresWithMovies()).thenThrow(new LegacyBoardRetiredException(),new LegacyBoardUnavailableException());
    when(charts.charts()).thenThrow(new LegacyBoardRetiredException(),new LegacyBoardUnavailableException());
    doThrow(new LegacyBoardRetiredException(),new LegacyBoardUnavailableException()).when(discord).execute(movie);
    doThrow(new LegacyBoardRetiredException(),new LegacyBoardUnavailableException()).when(discord).update(movie);
    when(movies.listGenres()).thenReturn(List.of());
    var mvc = MockMvcBuilders.standaloneSetup(
        new MovieSpringController(movies), new StatisticSpringController(charts), new DiscordRestrictedSpringController(discord))
        .setControllerAdvice(new LegacyBoardErrorAdvice()).build();
    for (var request : List.of(get("/movie/genres-with-movies"),get("/statistic/movies"),
        post("/restricted/discord/"+movie),patch("/restricted/discord/"+movie))) {
      mvc.perform(request).andExpect(status().isGone()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(header().doesNotExist("Location"))
          .andExpect(content().json("{\"code\":\"movie.legacy_board_retired\"}",true));
      mvc.perform(request).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(header().doesNotExist("Location"))
          .andExpect(content().json("{\"code\":\"movie.legacy_board_unavailable\"}",true));
    }
    mvc.perform(get("/movie/genres")).andExpect(status().isNoContent());
    verify(movies).listGenres();
  }

  @Test void transactionStartupErrorOnlyUsesLegacyEnvelopeForExplicitBoardHandler() throws Exception {
    var movies = mock(MovieController.class);
    var error = new CannotCreateTransactionException("private connection");
    when(movies.listAllGenresWithMovies()).thenThrow(error);
    when(movies.listGenres()).thenThrow(error);
    var mvc = MockMvcBuilders.standaloneSetup(new MovieSpringController(movies))
        .setControllerAdvice(new LegacyBoardErrorAdvice(),new SpringControllerAdviceHandler()).build();
    mvc.perform(get("/movie/genres-with-movies")).andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"movie.legacy_board_unavailable\"}",true));
    mvc.perform(get("/movie/genres")).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("contact the administrator"))
        .andExpect(jsonPath("$.path").value("/movie/genres"));
  }
}
