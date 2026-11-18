package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.moviemux.rankingcontent.adapter.controller.OwnRoundRatingController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.port.WriteOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.impl.WriteOwnRoundRatingUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnRoundRatingControllerTest {
  private final UUID ranking=UUID.randomUUID(),round=UUID.randomUUID(),user=UUID.randomUUID();
  private final WriteOwnRoundRating port=mock(WriteOwnRoundRating.class);
  private MockMvc mvc;
  private String path(){return "/rankings/"+ranking+"/rounds/"+round+"/rating/me";}
  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mvc=MockMvcBuilders.standaloneSetup(new OwnRoundRatingController(new WriteOwnRoundRatingUseCaseImpl(port)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(user,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private void authenticate(Principal principal){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));}
  @Test void createAndEditReturnOnlyOwnRoundAndScoreWithNoStore() throws Exception {
    for(int score:new int[]{0,10}) {
      when(port.create(ranking,round,user,score)).thenReturn(new OwnRoundRating(round,score));
      when(port.update(ranking,round,user,score)).thenReturn(new OwnRoundRating(round,score));
      for(var method:List.of(HttpMethod.POST,HttpMethod.PATCH))
        mvc.perform(request(method,path()).contentType(MediaType.APPLICATION_JSON).content("{\"score\":"+score+"}"))
            .andExpect(status().is(method==HttpMethod.POST?201:200)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().json("{\"round_id\":\""+round+"\",\"score\":"+score+"}",true));
    }
  }
  @Test void rejectsAllMissingNullOutOfRangeAndCoercedScoresBeforeWriting() throws Exception {
    for(String body:List.of("","null","{}","{","{\"score\":null}","{\"score\":-1}","{\"score\":11}",
        "{\"score\":8.9}","{\"score\":8.0}","{\"score\":1e0}","{\"score\":\"8\"}","{\"score\":true}","{\"score\":[]}","{\"score\":{}}","{\"score\":999999999999999999999999}"))
      for(var method:List.of(HttpMethod.POST,HttpMethod.PATCH))
        mvc.perform(request(method,path()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().json("{\"code\":\"ranking.invalid_rating\"}",true));
    verifyNoInteractions(port);
  }
  @Test void authenticatedIdentityCannotBeReplacedByBodyOrHeaders() throws Exception {
    when(port.create(ranking,round,user,7)).thenReturn(new OwnRoundRating(round,7));
    var body=new ObjectMapper().writeValueAsString(Map.of("score",7,"actor_id",UUID.randomUUID(),"author_id",UUID.randomUUID(),
        "user_id",UUID.randomUUID(),"ranking_id",UUID.randomUUID(),"round_id",UUID.randomUUID(),"roles",List.of("ADM"),"revealed",true));
    mvc.perform(request(HttpMethod.POST,path()).header("X-User-Id",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andExpect(content().json("{\"round_id\":\""+round+"\",\"score\":7}",true));
    verify(port).create(ranking,round,user,7);verifyNoMoreInteractions(port);
  }
  @Test void missingPrincipalOrAnyDisabledAccountFlagNeverWrites() throws Exception {
    for(var principal:List.of(new Principal(user,false,true,true,true),new Principal(user,true,false,true,true),
        new Principal(user,true,true,false,true),new Principal(user,true,true,true,false),new Principal(null,true,true,true,true))) {
      authenticate(principal);
      for(var method:List.of(HttpMethod.POST,HttpMethod.PATCH))
        mvc.perform(request(method,path()).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
    }
    SecurityContextHolder.clearContext();
    mvc.perform(request(HttpMethod.POST,path()).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}")).andExpect(status().isNotFound());
    verifyNoInteractions(port);
  }
  @Test void duplicatesMissingUpdatesArchiveAndDatabaseFailuresHaveStableCodes() throws Exception {
    when(port.create(ranking,round,user,8)).thenThrow(new DuplicateOwnRoundRatingException());
    expectError(HttpMethod.POST,409,"ranking.rating_exists");
    when(port.update(ranking,round,user,8)).thenThrow(new RankingEntriesDeniedException());
    expectError(HttpMethod.PATCH,404,"ranking.not_found");
    reset(port);when(port.update(ranking,round,user,8)).thenThrow(new ArchivedOwnRoundRatingException());
    expectError(HttpMethod.PATCH,409,"ranking.archived_read_only");
    for(RuntimeException failure:List.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),new org.springframework.transaction.TransactionSystemException("private tx"))) {
      reset(port);when(port.create(ranking,round,user,8)).thenThrow(failure);expectError(HttpMethod.POST,500,"ranking.operation_failed");
    }
  }
  @Test void invalidPathAndNullDirectBodyAreRejectedWithoutWrites() throws Exception {
    mvc.perform(request(HttpMethod.POST,"/rankings/bad/rounds/"+round+"/rating/me").contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}"))
        .andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"ranking.invalid_rating\"}",true));
    var controller=new OwnRoundRatingController(new WriteOwnRoundRatingUseCaseImpl(port));
    assertThatThrownBy(()->controller.create(ranking,round,null,user,new Principal(user,true,true,true,true))).isInstanceOf(InvalidOwnRoundRatingException.class);
    verifyNoInteractions(port);
  }
  private void expectError(HttpMethod method,int status,String code) throws Exception {
    mvc.perform(request(method,path()).contentType(MediaType.APPLICATION_JSON).content("{\"score\":8}"))
        .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsNotExpired,boolean notLocked){super("synthetic","unused",enabled,notExpired,credentialsNotExpired,notLocked,List.of());userid=id;}
    public UUID getUserid(){return userid;}
  }
}
