package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.ranking.adapter.controller.RankingContextSummaryController;
import com.moviemux.ranking.adapter.controller.RankingDirectoryController;
import com.moviemux.ranking.adapter.controller.RankingPersistenceErrorAdvice;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.CurrentRankingSummary;
import com.moviemux.ranking.domain.usecase.CreateRankingUseCase;
import com.moviemux.ranking.domain.usecase.ListRankingSummariesUseCase;
import com.moviemux.ranking.domain.usecase.impl.GetRankingSummaryUseCaseImpl;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

class RankingContextSummaryControllerTest {
  private static final UUID ACCOUNT = new UUID(0, 1);
  private static final UUID RANK = UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private final CurrentRankingSummary summaries = mock(CurrentRankingSummary.class);
  private final ListRankingSummariesUseCase directory = mock(ListRankingSummariesUseCase.class);
  private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(
            new RankingContextSummaryController(new GetRankingSummaryUseCaseImpl(summaries)),
            new RankingDirectoryController(mock(CreateRankingUseCase.class), directory))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new RankingPersistenceErrorAdvice())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(ACCOUNT, true, true, true, true));
  }

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void contextualResponseAddsOnlySharingProofToTheDirectoryShape() throws Exception {
    var item = new RankingSummary(RANK, null, RankingVisibility.PRIVATE, RankingAdmission.INVITATION, Set.of(RankingCapability.READ));
    when(summaries.find(ACCOUNT, RANK)).thenReturn(Optional.of(new RankingContextSummary(item, false)));
    when(directory.list(new RankingActor(ACCOUNT, true), null, 20)).thenReturn(new RankingSummaryPage(List.of(item), null));
    String listing = mvc.perform(get("/rankings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    var response = mvc.perform(get("/rankings/" + RANK.toString().toUpperCase(Locale.ROOT)).header("X-User-Id", UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("""
            {"ranking_id":"12345678-1234-1234-1234-123456789abc","name":null,
            "visibility":"PRIVATE","admission":"INVITATION","capabilities":["READ"],"can_share_open_link":false}
            """, true)).andReturn().getResponse();
    var directoryItem = mapper.readTree(listing).path("items").get(0);
    var context = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(response.getContentAsString());
    assertThat(context.remove("can_share_open_link").isBoolean()).isTrue();
    assertThat(directoryItem.has("can_share_open_link")).isFalse();
    assertThat(context).isEqualTo(directoryItem);
    verify(summaries).find(ACCOUNT, RANK);
    verifyNoMoreInteractions(summaries);
  }

  @Test void trueAndFalseAreStrictBooleansEvenForIdenticalFullCapabilities() throws Exception {
    var item = new RankingSummary(RANK, "Open", RankingVisibility.PRIVATE, RankingAdmission.OPEN,
        java.util.EnumSet.allOf(RankingCapability.class));
    when(summaries.find(ACCOUNT, RANK)).thenReturn(Optional.of(new RankingContextSummary(item, true)),
        Optional.of(new RankingContextSummary(item, false)));
    for (boolean expected : List.of(true, false)) {
      var response = mvc.perform(get("/rankings/" + RANK).header("X-Can-Share-Open-Link", !expected))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
          .andReturn().getResponse();
      var proof = mapper.readTree(response.getContentAsString()).path("can_share_open_link");
      assertThat(proof.isBoolean()).isTrue();
      assertThat(proof.booleanValue()).isEqualTo(expected);
    }
  }

  @Test void malformedPathAndUnavailableRankingReturnTheSameGenericNotFound() throws Exception {
    for (String malformed : List.of("not-a-uuid", "1-1-1-1-1", RANK + "0")) expectNotFound(malformed);
    verifyNoInteractions(summaries);
    when(summaries.find(ACCOUNT, RANK)).thenReturn(Optional.empty());
    expectNotFound(RANK.toString());
    verify(summaries).find(ACCOUNT, RANK);
  }

  @Test void absentIdAndEveryAccountFlagPreventRead() throws Exception {
    for (var principal : List.of(new Principal(null, true, true, true, true),
        new Principal(ACCOUNT, false, true, true, true), new Principal(ACCOUNT, true, false, true, true),
        new Principal(ACCOUNT, true, true, false, true), new Principal(ACCOUNT, true, true, true, false))) {
      authenticate(principal);
      expectNotFound(RANK.toString());
    }
    SecurityContextHolder.clearContext();
    expectNotFound(RANK.toString());
    verifyNoInteractions(summaries);
  }

  @Test void databaseDetailsAreRedactedAndNotCached() throws Exception {
    when(summaries.find(ACCOUNT, RANK)).thenThrow(new DataAccessResourceFailureException("private SQL"));
    mvc.perform(get("/rankings/" + RANK)).andExpect(status().isInternalServerError())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"code\":\"ranking.operation_failed\"}", true));
  }

  private void expectNotFound(String rank) throws Exception {
    mvc.perform(get("/rankings/" + rank)).andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().json("{\"code\":\"ranking.not_found\"}", true));
  }

  private void authenticate(Principal principal) {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null, "ROLE_ADM"));
  }

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id, boolean enabled, boolean notExpired, boolean credentialsValid, boolean notLocked) {
      super("synthetic", "unused", enabled, notExpired, credentialsValid, notLocked, List.of());
      userid = id;
    }
    public UUID getUserid() { return userid; }
  }
}
