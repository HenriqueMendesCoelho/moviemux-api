package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.RecoverableEntriesController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.RecoverableEntry;
import com.moviemux.rankingcontent.domain.model.RestoredEntry;
import com.moviemux.rankingcontent.domain.port.ListRecoverableEntries;
import com.moviemux.rankingcontent.domain.port.RestoreCurrentEntry;
import com.moviemux.rankingcontent.domain.usecase.impl.ManageRecoverableEntriesUseCaseImpl;
import java.time.OffsetDateTime;
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

class RecoverableEntriesControllerTest {
  private static final UUID USER=new UUID(0,1),RANK=new UUID(0,2),ENTRY=new UUID(0,3),NEXT=new UUID(0,4);
  private static final String CURRENT="/movies/collections/"+RANK+"/entries", LEGACY="/rankings/"+RANK+"/entries";
  private static final String RESTORE="/"+ENTRY+"/restore-current";
  private static final OffsetDateTime REMOVED=OffsetDateTime.parse("2026-12-01T09:00:00-03:00");
  private final ListRecoverableEntries directory=mock(ListRecoverableEntries.class);
  private final RestoreCurrentEntry restore=mock(RestoreCurrentEntry.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    var controller=new RecoverableEntriesController(new ManageRecoverableEntriesUseCaseImpl(directory,restore));
    mvc=MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void aliasesReturnSameAllowlistWithExactOpaqueVersionUtcAndRedactedTitle() throws Exception {
    long version=9_007_199_254_740_993L;
    when(directory.find(USER,RANK,null,21)).thenReturn(List.of(new RecoverableEntry(ENTRY,null,REMOVED,REMOVED.plusHours(24),version)));
    for(var base:List.of(CURRENT,LEGACY)){
      mvc.perform(request(HttpMethod.GET,base+"/recovery").header("X-User-Id",UUID.randomUUID()))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("""
              {"items":[{"entry_id":"%s","title":null,"removed_at":"2026-12-01T12:00:00Z",
                         "expires_at":"2026-12-02T12:00:00Z","version":"9007199254740993"}],"next_cursor":null}
              """.formatted(ENTRY),true));
    }
    verify(directory,times(2)).find(USER,RANK,null,21);verifyNoMoreInteractions(directory);verifyNoInteractions(restore);
  }

  @Test void lookaheadDoesNotLeakAndLastPublishedEntryDefinesCursor() throws Exception {
    when(directory.find(USER,RANK,RANK,2)).thenReturn(List.of(
        new RecoverableEntry(ENTRY,"Visible",REMOVED,REMOVED.plusHours(24),0),
        new RecoverableEntry(NEXT,"Unpublished",REMOVED,REMOVED.plusHours(24),2)));
    mvc.perform(request(HttpMethod.GET,CURRENT+"/recovery").queryParam("after",RANK.toString()).queryParam("limit","1"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("""
            {"items":[{"entry_id":"%s","title":"Visible","removed_at":"2026-12-01T12:00:00Z",
                       "expires_at":"2026-12-02T12:00:00Z","version":"0"}],"next_cursor":"%s"}
            """.formatted(ENTRY,ENTRY),true));
    verify(directory).find(USER,RANK,RANK,2);verifyNoMoreInteractions(directory);verifyNoInteractions(restore);
  }

  @Test void tokenlessRestoreBothAliasesUseExactDecimalWithoutExposingTokenOrTimes() throws Exception {
    when(restore.restoreCurrent(RANK,ENTRY,USER,Long.MAX_VALUE-1)).thenReturn(new RestoredEntry(ENTRY,Long.MAX_VALUE));
    for(var base:List.of(CURRENT,LEGACY)){
      mvc.perform(request(HttpMethod.POST,base+RESTORE).contentType(MediaType.APPLICATION_JSON)
          .content("{\"expected_version\":\"9223372036854775806\"}").header("X-Actor",UUID.randomUUID()))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"entry_id\":\""+ENTRY+"\",\"version\":\"9223372036854775807\"}",true));
    }
    verify(restore,times(2)).restoreCurrent(RANK,ENTRY,USER,Long.MAX_VALUE-1);
    verifyNoMoreInteractions(restore);verifyNoInteractions(directory);
  }

  @Test void invalidInputsNeverReachEitherPort() throws Exception {
    for(var bad:List.of("limit=0","limit=51","limit=2147483648","limit=3.0","limit=1&limit=2","after=short",
        "after="+RANK+"&after="+ENTRY,"foreign=1","limit=1&"))
      expect(HttpMethod.GET,CURRENT+"/recovery?"+bad,"",400,"ranking.invalid_recovery_request");
    expect(HttpMethod.GET,LEGACY+"/recovery","{}",400,"ranking.invalid_recovery_request");
    for(var bad:List.of("","{}","null","[]","{\"expected_version\":0}","{\"expected_version\":null}",
        "{\"expected_version\":\"0\",\"token\":\"token\"}",
        "{\"expected_version\":\"0\",\"expected_version\":\"1\"}",
        "{\"expected_version\":\"0\"} {}"," ".repeat(513)))
      expect(HttpMethod.POST,CURRENT+RESTORE,bad,400,"ranking.invalid_recovery_request");
    for(var version:List.of("","-1","00","01","+1","1.0","1e0"," 1","1 ","9223372036854775807","9223372036854775808"))
      expect(HttpMethod.POST,LEGACY+RESTORE,"{\"expected_version\":\""+version+"\"}",400,"ranking.invalid_recovery_request");
    expect(HttpMethod.POST,CURRENT+RESTORE+"?expected_version=0","{\"expected_version\":\"0\"}",400,"ranking.invalid_recovery_request");
    verifyNoInteractions(directory,restore);
  }

  @Test void compoundScopeAndActorAreRequiredAndDisabledFlagsCannotReachPorts() throws Exception {
    expect(HttpMethod.GET,CURRENT.replace(RANK.toString(),"1-1-1-1-1")+"/recovery","",404,"ranking.not_found");
    expect(HttpMethod.POST,CURRENT+RESTORE.replace(ENTRY.toString(),"bad"),"{\"expected_version\":\"0\"}",404,"ranking.not_found");
    for(var principal:List.of(new Principal(null,true,true,true,true),new Principal(USER,false,true,true,true),
        new Principal(USER,true,false,true,true),new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))){
      authenticate(principal);
      expect(HttpMethod.GET,CURRENT+"/recovery","",404,"ranking.not_found");
      expect(HttpMethod.POST,CURRENT+RESTORE,"{\"expected_version\":\"0\"}",404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(HttpMethod.GET,CURRENT+"/recovery","",404,"ranking.not_found");
    verifyNoInteractions(directory,restore);
  }

  @Test void authorityArchiveStaleExpiryAndPersistenceErrorsAreMinimal() throws Exception {
    for(var test:List.of(new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new ArchivedEntryRecoveryException(),409,"ranking.archived_read_only"),
        new Failure(new StaleEntryRecoveryException(),409,"ranking.entry_state_stale"),
        new Failure(new ExpiredEntryRecoveryException(),409,"ranking.entry_recovery_expired"),
        new Failure(new DataAccessResourceFailureException("private SQL"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private tx"),500,"ranking.operation_failed"))){
      reset(restore);when(restore.restoreCurrent(RANK,ENTRY,USER,0)).thenThrow(test.error());
      expect(HttpMethod.POST,CURRENT+RESTORE,"{\"expected_version\":\"0\"}",test.status(),test.code());
    }
    when(directory.find(USER,RANK,null,21)).thenThrow(new ArchivedEntryRecoveryException());
    expect(HttpMethod.GET,LEGACY+"/recovery","",409,"ranking.archived_read_only");
  }

  private void expect(HttpMethod method,String path,String body,int status,String code) throws Exception {
    var uri=java.net.URI.create(path);
    mvc.perform(request(method,uri).contentType(MediaType.APPLICATION_JSON).content(body)
        .with(req->{req.setQueryString(uri.getRawQuery());return req;}))
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
