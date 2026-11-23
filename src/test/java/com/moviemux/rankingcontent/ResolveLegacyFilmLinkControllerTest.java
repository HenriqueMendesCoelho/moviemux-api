package com.moviemux.rankingcontent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moviemux.rankingcontent.adapter.controller.ResolveLegacyFilmLinkController;
import com.moviemux.rankingcontent.domain.model.LegacyFilmLink;
import com.moviemux.rankingcontent.domain.port.ReadLegacyFilmLink;
import com.moviemux.rankingcontent.domain.usecase.impl.ResolveLegacyFilmLinkUseCaseImpl;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ResolveLegacyFilmLinkControllerTest {
  private final UUID actor = UUID.randomUUID(), rank = UUID.randomUUID(), entry = UUID.randomUUID();
  private final UUID legacy = UUID.fromString("a1158554-1fd1-4444-a8da-92748cace7d2");
  private final ReadLegacyFilmLink mappings = mock(ReadLegacyFilmLink.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new ResolveLegacyFilmLinkController(new ResolveLegacyFilmLinkUseCaseImpl(mappings)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    authenticate(new Principal(actor, true, true, true, true));
  }

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void aliasesReturnExactPairWithoutTrustingCallerHeadersOrRedirecting() throws Exception {
    when(mappings.find(actor, legacy)).thenReturn(Optional.of(new LegacyFilmLink(rank, entry)));
    String json = """
        {"ranking_id":"%s","entry_id":"%s"}
        """.formatted(rank, entry);
    for (String prefix : List.of("/movies/collections", "/rankings")) {
      mvc.perform(get(path(prefix, legacy.toString())).header("X-User-Id", UUID.randomUUID().toString())
              .header("X-Ranking-Id", UUID.randomUUID().toString()))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(header().doesNotExist("Location")).andExpect(content().json(json, true));
    }
    verify(mappings, org.mockito.Mockito.times(2)).find(actor, legacy);
    verifyNoMoreInteractions(mappings);
  }

  @Test void malformedUuidQueryBodyAndHeadNeverQueryMappings() throws Exception {
    String prefix = "/movies/collections";
    for (String id : List.of("bad", "1-2-3-4-5", legacy.toString().toUpperCase(Locale.ROOT))) {
      mvc.perform(get(path(prefix, id))).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().json("{\"code\":\"ranking.not_found\"}", true));
    }
    mvc.perform(get(path(prefix, legacy.toString()) + "?legacy=true")).andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get(path(prefix, legacy.toString())).content("body")).andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(head(path(prefix, legacy.toString()))).andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(mappings);
  }

  @Test void disabledLockedExpiredAndAnonymousPrincipalsDoNotReachMappings() throws Exception {
    for (Principal principal : List.of(new Principal(actor, false, true, true, true),
        new Principal(actor, true, false, true, true), new Principal(actor, true, true, false, true),
        new Principal(actor, true, true, true, false))) {
      authenticate(principal);
      expectError(404, "ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expectError(404, "ranking.not_found"); // Standalone MVC bypasses the real baseline auth filter.
    verifyNoInteractions(mappings);
  }

  @Test void noMappingIsUniformAndDatabaseFailureIsSanitized() throws Exception {
    when(mappings.find(actor, legacy)).thenReturn(Optional.empty())
        .thenThrow(new DataAccessResourceFailureException("synthetic private database detail"));
    expectError(404, "ranking.not_found");
    expectError(500, "ranking.operation_failed");
  }

  private void expectError(int statusCode, String code) throws Exception {
    mvc.perform(get(path("/movies/collections", legacy.toString()))).andExpect(status().is(statusCode))
        .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Location"))
        .andExpect(content().json("{\"code\":\"" + code + "\"}", true));
  }

  private String path(String prefix, String id) { return prefix + "/legacy-film-links/" + id; }

  private void authenticate(Principal principal) {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_ADM"));
  }

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id, boolean enabled, boolean notExpired, boolean credentialsNotExpired, boolean notLocked) {
      super("synthetic", "unused", enabled, notExpired, credentialsNotExpired, notLocked, List.of());
      userid = id;
    }
    public UUID getUserid() { return userid; }
  }
}
