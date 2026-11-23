package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.RepeatedRoundController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import com.moviemux.rankingcontent.domain.port.RepeatedRoundOperations;
import com.moviemux.rankingcontent.domain.usecase.impl.ManageRepeatedRoundsUseCaseImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class RepeatedRoundControllerTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3),
      COMMAND=new UUID(0,4), ROUND=new UUID(0,5);
  private static final String SETTINGS="/rankings/"+RANK+"/settings/rounds", CREATE="/rankings/"+RANK+"/entries/"+ENTRY+"/rounds";
  private static final String COMMAND_BODY="{\"command_id\":\""+COMMAND+"\"}";
  private final RepeatedRoundOperations rounds=mock(RepeatedRoundOperations.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mvc=MockMvcBuilders.standaloneSetup(new RepeatedRoundController(new ManageRepeatedRoundsUseCaseImpl(rounds)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void readsAndSetsStrictBooleanWithoutUsingClientActor() throws Exception {
    when(rounds.settings(RANK,USER)).thenReturn(false);
    when(rounds.setSettings(RANK,USER,true)).thenReturn(true);
    mvc.perform(request(HttpMethod.GET,SETTINGS).header("X-User-Id",UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"repeat_enabled\":false}",true));
    mvc.perform(request(HttpMethod.PUT,SETTINGS).contentType(MediaType.APPLICATION_JSON)
        .content("{\"repeat_enabled\":true}").header("X-Owner",UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"repeat_enabled\":true}",true));
    verify(rounds).settings(RANK,USER);verify(rounds).setSettings(RANK,USER,true);verifyNoMoreInteractions(rounds);
  }

  @Test void createAndReplayReturnSameAllowlistAndCanonicalizeDescription() throws Exception {
    when(rounds.create(RANK,ENTRY,USER,COMMAND,null)).thenReturn(new RepeatedRoundReceipt(ROUND,2,true),
        new RepeatedRoundReceipt(ROUND,2,false),new RepeatedRoundReceipt(ROUND,2,false));
    for (String body:List.of(COMMAND_BODY,COMMAND_BODY.replace("}",",\"description\":null}"),
        COMMAND_BODY.replace("}",",\"description\":\"　  \"}"))){
      int expected=body.equals(COMMAND_BODY)?201:200;
      mvc.perform(request(HttpMethod.POST,CREATE).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"round_id\":\""+ROUND+"\",\"round_number\":2}",true));
    }
    verify(rounds,times(3)).create(RANK,ENTRY,USER,COMMAND,null);verifyNoMoreInteractions(rounds);
  }

  @Test void malformedSettingsAndQueryNeverCallStorage() throws Exception {
    for(String body:List.of("","null","{}","{\"repeat_enabled\":null}","{\"repeat_enabled\":0}",
        "{\"repeat_enabled\":\"false\"}","{\"repeat_enabled\":true,\"repeat_enabled\":false}",
        "{\"repeat_enabled\":true,\"owner\":true}","{\"repeat_enabled\":true} {}"," ".repeat(4097)))
      expect(HttpMethod.PUT,SETTINGS,body,400,"ranking.invalid_round_settings");
    expect(HttpMethod.GET,SETTINGS,"{}",400,"ranking.invalid_round_settings");
    expect(HttpMethod.GET,SETTINGS+"?repeat_enabled=true","",400,"ranking.invalid_round_settings");
    expect(HttpMethod.PUT,SETTINGS+"?x=1","{\"repeat_enabled\":true}",400,"ranking.invalid_round_settings");
    verifyNoInteractions(rounds);
  }

  @Test void malformedCommandMissingIdDescriptionTypeDuplicateOversizeAndQueryNeverCallStorage() throws Exception {
    for(String body:List.of("","{}","null","[]","{\"command_id\":null}","{\"command_id\":\"1-1-1-1-1\"}",
        COMMAND_BODY.replace("}",",\"command_id\":\""+COMMAND+"\"}"),
        COMMAND_BODY.replace("}",",\"owner\":true}"),COMMAND_BODY.replace("}",",\"description\":4}"),
        COMMAND_BODY.replace("}",",\"description\":{},\"foo\":true}"),
        COMMAND_BODY.replace("}",",\"description\":\""+("🎬".repeat(241))+"\"}"),COMMAND_BODY+"{}",COMMAND_BODY+" ".repeat(4096)))
      expect(HttpMethod.POST,CREATE,body,400,"ranking.invalid_round_command");
    expect(HttpMethod.POST,CREATE+"?actor=other",COMMAND_BODY,400,"ranking.invalid_round_command");
    verifyNoInteractions(rounds);
  }

  @Test void malformedScopeAndDisabledPrincipalReturnGeneric404WithoutLookup() throws Exception {
    expect(HttpMethod.GET,SETTINGS.replace(RANK.toString(),"a-b-c-d-e"),"",404,"ranking.not_found");
    expect(HttpMethod.POST,CREATE.replace(ENTRY.toString(),"bad"),COMMAND_BODY,404,"ranking.not_found");
    for(var principal:List.of(new Principal(null,true,true,true,true),new Principal(USER,false,true,true,true),
        new Principal(USER,true,false,true,true),new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))){
      authenticate(principal);
      expect(HttpMethod.GET,SETTINGS,"",404,"ranking.not_found");
      expect(HttpMethod.PUT,SETTINGS,"{\"repeat_enabled\":true}",404,"ranking.not_found");
      expect(HttpMethod.POST,CREATE,COMMAND_BODY,404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(HttpMethod.GET,SETTINGS,"",404,"ranking.not_found");
    verifyNoInteractions(rounds);
  }

  @Test void exactConflictNotFoundAndSanitizedOperationFailureCodes() throws Exception {
    for(var test:List.of(new Failure(new RepeatRoundsDisabledException(),409,"ranking.repeat_rounds_disabled"),
        new Failure(new RoundCommandConflictException(),409,"ranking.round_command_conflict"),
        new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new DataAccessResourceFailureException("private SQL"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private transaction"),500,"ranking.operation_failed"))){
      reset(rounds);
      when(rounds.create(RANK,ENTRY,USER,COMMAND,null)).thenThrow(test.error());
      expect(HttpMethod.POST,CREATE,COMMAND_BODY,test.status(),test.code());
    }
  }

  private void expect(HttpMethod method,String path,String body,int status,String code) throws Exception {
    mvc.perform(request(method,path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
  private void authenticate(Principal principal){
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }
  private record Failure(RuntimeException error,int status,String code) {}

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsValid,boolean notLocked){
      super("synthetic","unused",enabled,notExpired,credentialsValid,notLocked,List.of());userid=id;
    }
    public UUID getUserid(){return userid;}
  }
}
