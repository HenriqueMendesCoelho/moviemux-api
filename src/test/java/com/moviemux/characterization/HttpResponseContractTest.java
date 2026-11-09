package com.moviemux.characterization;

import com.moviemux.application.spring.handler.SpringControllerAdviceHandler;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.application.spring.controller.MovieSpringController;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.application.spring.controller.UserPublicSpringController;
import com.moviemux.user.application.spring.controller.UserRestrictedSpringController;
import com.moviemux.user.usecase.exception.DuplicatedUserException;
import com.moviemux.user.usecase.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Transport characterization only: security filters are tested separately. */
class HttpResponseContractTest {
    private UserController users;
    private MovieController movies;
    private MockMvc mvc;
    private static final String USER = """
            {"name":"Synthetic User","email":"synthetic@example.test","password":"Synthetic1!","notify":true,"inviteCode":"fixture"}
            """;
    @BeforeEach void setup() {
        users = mock(UserController.class);
        movies = mock(MovieController.class);
        mvc = MockMvcBuilders.standaloneSetup(new UserPublicSpringController(users),
                new UserRestrictedSpringController(users), new MovieSpringController(movies))
                .setControllerAdvice(new SpringControllerAdviceHandler()).build();
    }
    @Test void duplicateRegistrationKeepsNumericStatusAndBusinessMessage() throws Exception {
        when(users.createUser(any())).thenThrow(new DuplicatedUserException());
        mvc.perform(post("/public/user").contentType(MediaType.APPLICATION_JSON).content(USER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(true))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("user email already used"));
    }
    @Test void invalidRegistrationHasFieldErrorsAndDoesNotReachUseCase() throws Exception {
        mvc.perform(post("/public/user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/public/user"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(users);
    }
    @Test void unexpectedFailureKeepsStringStatusAndHidesInternalMessage() throws Exception {
        when(users.createUser(any())).thenThrow(new IllegalStateException("private diagnostic"));
        mvc.perform(post("/public/user").contentType(MediaType.APPLICATION_JSON).content(USER))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.status").value("500"))
                .andExpect(jsonPath("$.message").value("contact the administrator"))
                .andExpect(jsonPath("$.error").value("internal server error"));
    }
    @Test void resetRequestReturnsEmptyOkAndDelegatesEmail() throws Exception {
        mvc.perform(post("/public/user/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"synthetic@example.test\"}"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        verify(users).createRedefinePasswordKey(argThat(request -> request.getEmail().equals("synthetic@example.test")));
    }
    @Test void missingAdminUsersReturnsNoContent() throws Exception {
        when(users.getAllUsers()).thenThrow(new UserNotFoundException());
        mvc.perform(get("/restricted/user")).andExpect(status().isNoContent()).andExpect(content().string(""));
    }
    @Test void emptyGenresReturnNoContentRatherThanEmptyArray() throws Exception {
        when(movies.listGenres()).thenReturn(List.of());
        mvc.perform(get("/movie/genres")).andExpect(status().isNoContent()).andExpect(content().string(""));
    }
    @Test void emptyGenresWithMoviesReturnNoContent() throws Exception {
        when(movies.listAllGenresWithMovies()).thenReturn(List.of());
        mvc.perform(get("/movie/genres-with-movies")).andExpect(status().isNoContent());
    }
}
