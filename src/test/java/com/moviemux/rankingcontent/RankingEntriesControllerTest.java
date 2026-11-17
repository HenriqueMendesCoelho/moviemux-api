package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.RankingEntriesController;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.usecase.impl.ListRankingEntriesUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RankingEntriesControllerTest {
  private final UUID rank=UUID.randomUUID(),user=UUID.randomUUID();
  @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
  private MockMvc mvc(com.moviemux.rankingcontent.domain.port.CurrentRankingEntries entries) {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new Principal(user),null));
    var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    return MockMvcBuilders.standaloneSetup(new RankingEntriesController(new ListRankingEntriesUseCaseImpl(entries)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
  }
  @Test void defaultAndExplicitPaginationAreParsedAndNullMetadataIsPreserved() throws Exception {
    var entry=new RankingMovieEntryView(UUID.randomUUID(),UUID.randomUUID(),null,null,"English","Português",null,null,null,OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    var mvc=mvc((r,a,c,l)->{assertThat(r).isEqualTo(rank);assertThat(a).isEqualTo(user);assertThat(c).isNull();assertThat(l).isEqualTo(21);return Optional.of(new RankingEntriesSnapshot(rank,user,List.of(entry)));});
    var response=mvc.perform(get("/rankings/"+rank+"/entries")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
    var body=new ObjectMapper().readTree(response);assertThat(body.has("next_cursor")).isTrue();assertThat(body.get("next_cursor").isNull()).isTrue();
    var item=body.at("/items/0");assertThat(item.size()).isEqualTo(10);assertThat(item.has("runtime")).isTrue();assertThat(item.get("runtime").isNull()).isTrue();assertThat(item.get("original_title").isNull()).isTrue();
    UUID cursor=UUID.randomUUID();
    mvc=mvc((r,a,c,l)->{assertThat(c).isEqualTo(cursor);assertThat(l).isEqualTo(101);return Optional.of(new RankingEntriesSnapshot(rank,user,List.of()));});
    mvc.perform(get("/rankings/"+rank+"/entries").param("after",cursor.toString()).param("limit","100")).andExpect(status().isOk());
  }
  @Test void malformedQueriesAndInvalidLimitsHaveStableNoStoreErrors() throws Exception {
    var mvc=mvc((r,a,c,l)->{throw new AssertionError("Invalid query must not read");});
    for(String limit:List.of("0","-1","101","nope"))mvc.perform(get("/rankings/"+rank+"/entries").param("limit",limit))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("ranking.invalid_page"));
    mvc.perform(get("/rankings/"+rank+"/entries").param("after","wrong")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ranking.invalid_page"));
  }
  @Test void missingPrincipalOrDeniedSnapshotNeverReturnsMetadata() throws Exception {
    var mvc=mvc((r,a,c,l)->Optional.empty());
    mvc.perform(get("/rankings/"+rank+"/entries")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("ranking.not_found"));
    SecurityContextHolder.clearContext();
    mvc.perform(get("/rankings/"+rank+"/entries")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ranking.not_found"));
  }
  @Test void databaseFailureIsSanitized() throws Exception {
    var mvc=mvc((r,a,c,l)->{throw new org.springframework.dao.DataAccessResourceFailureException("private SQL details");});
    mvc.perform(get("/rankings/"+rank+"/entries")).andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.operation_failed\"}",true));
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID userid){super("synthetic","unused",List.of());this.userid=userid;}
    public UUID getUserid(){return userid;}
  }
}
