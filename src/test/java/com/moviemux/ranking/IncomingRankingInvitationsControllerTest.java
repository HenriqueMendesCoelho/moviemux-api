package com.moviemux.ranking;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moviemux.ranking.adapter.controller.IncomingRankingInvitationsController;
import com.moviemux.ranking.adapter.controller.RankingPersistenceErrorAdvice;
import com.moviemux.ranking.domain.model.IncomingRankingInvitation;
import com.moviemux.ranking.domain.port.ListCurrentIncomingRankingInvitations;
import com.moviemux.ranking.domain.usecase.impl.ListIncomingRankingInvitationsUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IncomingRankingInvitationsControllerTest {
  private static final String PATH = "/ranking-invitations/incoming";
  private static final UUID ACTOR = new UUID(0,1), RANK = new UUID(0,2), INVITE = new UUID(0,3), NEXT = new UUID(0,4);
  private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-11-30T12:00:00Z");
  private final ListCurrentIncomingRankingInvitations inbox = mock(ListCurrentIncomingRankingInvitations.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    var mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc = MockMvcBuilders.standaloneSetup(new IncomingRankingInvitationsController(new ListIncomingRankingInvitationsUseCaseImpl(inbox)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new RankingPersistenceErrorAdvice())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(ACTOR, true, true, true, true));
  }

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void defaultPageReturnsOnlyExactAllowlistAndExplicitNullCursor() throws Exception {
    when(inbox.find(ACTOR,null,21)).thenReturn(List.of(new IncomingRankingInvitation(INVITE,RANK,null,EXPIRY)));
    mvc.perform(get(PATH).header("X-User-Id", UUID.randomUUID()).param("user_id", UUID.randomUUID().toString()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("""
            {"items":[{"invite_id":"00000000-0000-0000-0000-000000000003",
            "ranking_id":"00000000-0000-0000-0000-000000000002","ranking_name":null,"expires_at":"2026-11-30T12:00:00Z"}],
            "next_cursor":null}
            """, true));
    verify(inbox).find(ACTOR,null,21);
    verifyNoMoreInteractions(inbox);
  }

  @Test void explicitPageKeepsLookaheadOutAndUsesOnlyPublishedInviteId() throws Exception {
    when(inbox.find(ACTOR,RANK,2)).thenReturn(List.of(new IncomingRankingInvitation(INVITE,RANK,null,EXPIRY),
        new IncomingRankingInvitation(NEXT,new UUID(0,5),"Example",EXPIRY)));
    mvc.perform(get(PATH).param("after",RANK.toString()).param("limit","1"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.next_cursor").value(INVITE.toString()));
    verify(inbox).find(ACTOR,RANK,2);
  }

  @Test void invalidPageParametersNeverReachStorage() throws Exception {
    for (var request : List.of(get(PATH).param("after","not-uuid"), get(PATH).param("limit","bad"),
        get(PATH).param("limit","0"), get(PATH).param("limit","101"))) {
      mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\"ranking.invalid_page\"}",true));
    }
    verifyNoInteractions(inbox);
  }

  @Test void missingPrincipalAndEachDisabledFlagNeverReachStorage() throws Exception {
    for (var principal : List.of(new Principal(null,true,true,true,true), new Principal(ACTOR,false,true,true,true),
        new Principal(ACTOR,true,false,true,true), new Principal(ACTOR,true,true,false,true),
        new Principal(ACTOR,true,true,true,false))) {
      authenticate(principal);
      expectDenied();
    }
    SecurityContextHolder.clearContext();
    expectDenied();
    verifyNoInteractions(inbox);
  }

  @Test void databaseErrorRemainsGenericAndNoStore() throws Exception {
    when(inbox.find(ACTOR,null,21)).thenThrow(new DataAccessResourceFailureException("private statement"));
    mvc.perform(get(PATH)).andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.operation_failed\"}",true));
  }

  private void expectDenied() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invitation_not_found\"}",true));
  }

  private void authenticate(Principal principal) {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id, boolean enabled, boolean notExpired, boolean credentialsValid, boolean notLocked) {
      super("synthetic","unused",enabled,notExpired,credentialsValid,notLocked,List.of());
      userid=id;
    }
    public UUID getUserid() { return userid; }
  }
}
