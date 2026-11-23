package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moviemux.ranking.adapter.controller.JoinOpenRankingController;
import com.moviemux.ranking.adapter.controller.RankingPersistenceErrorAdvice;
import com.moviemux.ranking.domain.exception.InvalidRankingJoinException;
import com.moviemux.ranking.domain.port.JoinOpenRanking;
import com.moviemux.ranking.domain.usecase.impl.JoinOpenRankingUseCaseImpl;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class JoinOpenRankingControllerTest {
  private static final UUID RANK = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private static final UUID USER = new UUID(0, 2);
  private static final String PATH = "/rankings/" + RANK + "/join";
  private final JoinOpenRanking rankings = mock(JoinOpenRanking.class);
  private final JoinOpenRankingController controller = new JoinOpenRankingController(new JoinOpenRankingUseCaseImpl(rankings));
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new RankingPersistenceErrorAdvice()).build();
    authenticate(new Principal(USER, true, true, true, true));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void actorComesOnlyFromPrincipalAndSuccessfulRetryHasEmptyNoStoreResponse() throws Exception {
    when(rankings.join(RANK, USER)).thenReturn(true);
    for (String path : List.of(PATH, "/rankings/" + RANK.toString().toUpperCase(Locale.ROOT) + "/join")) {
      mvc.perform(post(path).header("X-User-Id", UUID.randomUUID()))
          .andExpect(status().isNoContent()).andExpect(content().string(""))
          .andExpect(header().string("Cache-Control", "no-store"));
    }
    verify(rankings, times(2)).join(RANK, USER);
    verifyNoMoreInteractions(rankings);
  }

  @Test void nonEmptyBodiesAndQueryAreRejectedBeforeStorage() throws Exception {
    for (String body : List.of("{}", " ", "null", "[]", "123")) {
      mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().json("{\"code\":\"ranking.invalid_join\"}", true));
    }
    mvc.perform(post(PATH).queryParam("actor", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invalid_join\"}", true));
    verifyNoInteractions(rankings);
  }

  @Test void unknownLengthUsesAtMostOneByteAndReadFailureIsRedacted() throws Exception {
    var request = mock(HttpServletRequest.class);
    var stream = mock(ServletInputStream.class);
    var principal = new Principal(USER, true, true, true, true);
    when(request.getContentLengthLong()).thenReturn(-1L);
    when(request.getInputStream()).thenReturn(stream);
    when(stream.read()).thenReturn((int)'{').thenThrow(new IOException("private transport detail"));
    for (int i=0; i<2; i++) {
      assertThatThrownBy(() -> controller.join(RANK.toString(), request, USER, principal))
          .isInstanceOf(InvalidRankingJoinException.class).hasNoCause();
    }
    verify(stream, times(2)).read();
    verifyNoMoreInteractions(stream);
    verifyNoInteractions(rankings);
  }

  @Test void hugeDeclaredBodyAndQueryNeverReadStream() throws Exception {
    var request = mock(HttpServletRequest.class);
    var principal = new Principal(USER, true, true, true, true);
    when(request.getContentLengthLong()).thenReturn(Long.MAX_VALUE);
    assertThatThrownBy(() -> controller.join(RANK.toString(), request, USER, principal))
        .isInstanceOf(InvalidRankingJoinException.class);
    when(request.getQueryString()).thenReturn("");
    assertThatThrownBy(() -> controller.join(RANK.toString(), request, USER, principal))
        .isInstanceOf(InvalidRankingJoinException.class);
    verify(request, never()).getInputStream();
    verifyNoInteractions(rankings);
  }

  @Test void malformedNonCanonicalAndDeniedScopeHaveSame404() throws Exception {
    for (String invalid : List.of("no", "1-1-1-1-1", RANK+"0")) {
      expect("/rankings/"+invalid+"/join", 404, "ranking.not_found");
    }
    verifyNoInteractions(rankings);
    when(rankings.join(RANK, USER)).thenReturn(false);
    expect(PATH, 404, "ranking.not_found");
    verify(rankings).join(RANK, USER);
  }

  @Test void missingPrincipalAndEachDisabledAccountFlagNeverJoin() throws Exception {
    for (var principal : List.of(new Principal(USER, false, true, true, true), new Principal(USER, true, false, true, true),
        new Principal(USER, true, true, false, true), new Principal(USER, true, true, true, false),
        new Principal(null, true, true, true, true))) {
      authenticate(principal);
      expect(PATH, 404, "ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(PATH, 404, "ranking.not_found");
    verifyNoInteractions(rankings);
  }

  @Test void navigatingGetOrHeadCannotJoin() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
    mvc.perform(head(PATH)).andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(rankings);
  }

  @Test void transactionAndDatabaseFailuresAreGloballyRedacted() throws Exception {
    for(var failure : List.of(new DataAccessResourceFailureException("private SQL"),
        new TransactionSystemException("private transaction"))) {
      reset(rankings);
      when(rankings.join(RANK, USER)).thenThrow(failure);
      expect(PATH, 500, "ranking.operation_failed");
    }
  }

  private void expect(String path, int status, String code) throws Exception {
    mvc.perform(post(path)).andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}", true));
  }

  private void authenticate(Principal principal) {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_ADM"));
  }

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id, boolean enabled, boolean notExpired, boolean credentialsValid, boolean notLocked) {
      super("synthetic", "unused", enabled, notExpired, credentialsValid, notLocked, List.of()); userid=id;
    }
    public UUID getUserid() { return userid; }
  }
}
