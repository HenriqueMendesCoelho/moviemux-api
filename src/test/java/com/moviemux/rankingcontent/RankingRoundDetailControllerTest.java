package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.RankingRoundDetailController;
import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import com.moviemux.rankingcontent.domain.port.ReadRoundAndRevealIfDue;
import com.moviemux.rankingcontent.domain.usecase.impl.ReadRankingRoundUseCaseImpl;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
import org.springframework.transaction.TransactionSystemException;

class RankingRoundDetailControllerTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3);
  private static final UUID ROUND=UUID.fromString("12345678-1234-1234-1234-123456789abc");
  private final ReadRoundAndRevealIfDue rounds=mock(ReadRoundAndRevealIfDue.class);
  private MockMvc mvc;
  private String path(Object rank,Object entry,Object round){return "/rankings/"+rank+"/entries/"+entry+"/rounds/"+round;}
  private String path(){return path(RANK,ENTRY,ROUND);}

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new RankingRoundDetailController(new ReadRankingRoundUseCaseImpl(rounds)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void responseContainsOnlyTheThreeFieldsAndExplicitNullWithNoStore() throws Exception {
    for(Boolean revealed:Arrays.asList(null,Boolean.FALSE,Boolean.TRUE)){
      when(rounds.read(RANK,ENTRY,ROUND,USER)).thenReturn(Optional.of(new RoundRevealView(ROUND,12,revealed)));
      mvc.perform(get(path(RANK,ENTRY,ROUND.toString().toUpperCase(Locale.ROOT))).header("X-User-Id",UUID.randomUUID())
          .queryParam("actor",UUID.randomUUID().toString())).andExpect(status().isOk())
          .andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"round_id\":\""+ROUND+"\",\"round_number\":12,\"revealed\":"+revealed+"}",true));
    }
    verify(rounds,times(3)).read(RANK,ENTRY,ROUND,USER);
    verifyNoMoreInteractions(rounds);
  }

  @Test void malformedIdsAndMissingRoundHaveTheSameGenericNotFound() throws Exception {
    for(var malformed:List.of(path("x",ENTRY,ROUND),path(RANK,"1-1-1-1-1",ROUND),path(RANK,ENTRY,ROUND+"0")))
      expect(malformed,404,"ranking.not_found");
    verifyNoInteractions(rounds);
    when(rounds.read(RANK,ENTRY,ROUND,USER)).thenReturn(Optional.empty());
    expect(path(),404,"ranking.not_found");
    verify(rounds).read(RANK,ENTRY,ROUND,USER);
  }

  @Test void missingPrincipalOrAnyDisabledFlagNeverReachesPort() throws Exception {
    for(var principal:List.of(new Principal(null,true,true,true,true),new Principal(USER,false,true,true,true),
        new Principal(USER,true,false,true,true),new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))){
      authenticate(principal);
      expect(path(),404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(path(),404,"ranking.not_found");
    verifyNoInteractions(rounds);
  }

  @Test void headDoesNotImplicitlyReadOrReveal() throws Exception {
    mvc.perform(head(path())).andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow","GET")).andExpect(header().string("Cache-Control","no-store"));
    verifyNoInteractions(rounds);
  }

  @Test void persistenceAndCommitFailuresHaveMinimalGenericError() throws Exception {
    for(var error:List.of(new DataAccessResourceFailureException("private SQL"),
        new TransactionSystemException("private transaction"))){
      reset(rounds);
      when(rounds.read(RANK,ENTRY,ROUND,USER)).thenThrow(error);
      expect(path(),500,"ranking.operation_failed");
    }
  }

  private void expect(String path,int status,String code) throws Exception {
    mvc.perform(get(path)).andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
  private void authenticate(Principal principal){
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsValid,boolean notLocked){
      super("synthetic","unused",enabled,notExpired,credentialsValid,notLocked,List.of());userid=id;
    }
    public UUID getUserid(){return userid;}
  }
}
