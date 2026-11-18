package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.moviemux.rankingcontent.adapter.controller.GetOwnRoundRatingController;
import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import com.moviemux.rankingcontent.domain.port.CurrentOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.impl.GetOwnRoundRatingUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GetOwnRoundRatingControllerTest {
  private final UUID rank=UUID.randomUUID(),round=UUID.randomUUID(),user=UUID.randomUUID();
  private final CurrentOwnRoundRating port=mock(CurrentOwnRoundRating.class);
  private MockMvc mvc;
  private String path(){return "/rankings/"+rank+"/rounds/"+round+"/rating/me";}
  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new GetOwnRoundRatingController(new GetOwnRoundRatingUseCaseImpl(port)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(user,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private void authenticate(Principal principal){
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }
  @Test void explicitNullAndOutliersArePreservedWithOnlyTwoFields() throws Exception {
    for(Integer score:Arrays.asList(null,-1,0,10,11,Integer.MAX_VALUE)) {
      when(port.find(rank,round,user)).thenReturn(Optional.of(new OwnRoundRatingView(round,score)));
      mvc.perform(get(path())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"round_id\":\""+round+"\",\"score\":"+score+"}",true));
    }
  }
  @Test void forgedIdentityHeadersAndQueryNeverReplacePrincipal() throws Exception {
    when(port.find(rank,round,user)).thenReturn(Optional.of(new OwnRoundRatingView(round,7)));
    mvc.perform(get(path()).header("X-User-Id",UUID.randomUUID())
        .param("user_id",UUID.randomUUID().toString()).param("roles","ADM"))
        .andExpect(status().isOk()).andExpect(content().json("{\"round_id\":\""+round+"\",\"score\":7}",true));
    verify(port).find(rank,round,user);verifyNoMoreInteractions(port);
  }
  @Test void missingPrincipalAndEachInvalidFlagPreventReads() throws Exception {
    for(var principal:List.of(new Principal(user,false,true,true,true),new Principal(user,true,false,true,true),
        new Principal(user,true,true,false,true),new Principal(user,true,true,true,false),new Principal(null,true,true,true,true))) {
      authenticate(principal);expectError(404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();expectError(404,"ranking.not_found");verifyNoInteractions(port);
  }
  @Test void missingAndInaccessibleAreUniformAndDatabaseErrorsAreSanitized() throws Exception {
    when(port.find(rank,round,user)).thenReturn(Optional.empty());
    expectError(404,"ranking.not_found");
    for(RuntimeException failure:List.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),
        new org.springframework.transaction.TransactionSystemException("private tx"))) {
      reset(port);when(port.find(rank,round,user)).thenThrow(failure);expectError(500,"ranking.operation_failed");
    }
  }
  @Test void malformedPathDoesNotReachPort() throws Exception {
    mvc.perform(get("/rankings/bad/rounds/"+round+"/rating/me"))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invalid_rating\"}",true));
    verifyNoInteractions(port);
  }
  private void expectError(int status,String code) throws Exception {
    mvc.perform(get(path())).andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsNotExpired,boolean notLocked){
      super("synthetic","unused",enabled,notExpired,credentialsNotExpired,notLocked,List.of());userid=id;
    }
    public UUID getUserid(){return userid;}
  }
}
