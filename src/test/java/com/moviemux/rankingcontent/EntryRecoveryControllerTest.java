package com.moviemux.rankingcontent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.moviemux.rankingcontent.adapter.controller.EntryRecoveryController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import com.moviemux.rankingcontent.domain.port.EntryRecovery;
import com.moviemux.rankingcontent.domain.usecase.impl.EntryRecoveryUseCaseImpl;
import java.time.OffsetDateTime;
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
class EntryRecoveryControllerTest {
  private final UUID rank=UUID.randomUUID(),entry=UUID.randomUUID(),user=UUID.randomUUID(),token=UUID.randomUUID();
  private final EntryRecovery port=mock(EntryRecovery.class);private MockMvc mvc;
  private String path(){return "/rankings/"+rank+"/entries/"+entry;}
  @BeforeEach void setup(){
    authenticate(new Principal(user,true,true,true,true));
    var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new EntryRecoveryController(new EntryRecoveryUseCaseImpl(port)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private void authenticate(Principal p){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(p,null,"ROLE_ADM"));}
  @Test void previewIsMetadataOnlyWithExplicitNullsLocalizedWarningAndNoStore() throws Exception {
    when(port.preview(rank,entry,user)).thenReturn(new EntryRecoveryState(entry,0,null,null,null));
    mvc.perform(request(HttpMethod.GET,path()+"/removal-preview")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"entry_id\":\""+entry+"\",\"version\":0,\"removed_at\":null,\"expires_at\":null,\"removal_token\":null,\"warning_code\":\"ranking.entry_removal_permanent_after_retention\",\"recovery_seconds\":86400}",true));
  }
  @Test void removalAndRestoreUseVersionAndTokenNotBodyAuthority() throws Exception {
    var now=OffsetDateTime.parse("2026-01-01T00:00:00Z");
    when(port.remove(rank,entry,user,0)).thenReturn(new EntryRecoveryState(entry,1,now,now.plusHours(24),token));
    when(port.restore(rank,entry,user,token)).thenReturn(new EntryRecoveryState(entry,2,null,null,null));
    mvc.perform(request(HttpMethod.DELETE,path()).contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0,\"confirm\":true,\"actor_id\":\""+UUID.randomUUID()+"\",\"capabilities\":[\"REMOVE_ENTRY\"]}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.removal_token").value(token.toString()));
    mvc.perform(request(HttpMethod.POST,path()+"/restore").contentType(MediaType.APPLICATION_JSON).content("{\"removal_token\":\""+token+"\"}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.version").value(2));
    verify(port).remove(rank,entry,user,0);verify(port).restore(rank,entry,user,token);verifyNoMoreInteractions(port);
  }
  @Test void malformedConfirmationVersionAndTokenNeverReachPorts() throws Exception {
    for(String body:List.of("","null","{}","{\"expected_version\":0}","{\"expected_version\":0,\"confirm\":false}",
        "{\"expected_version\":0,\"confirm\":\"true\"}","{\"expected_version\":null,\"confirm\":true}","{\"expected_version\":-1,\"confirm\":true}",
        "{\"expected_version\":1.0,\"confirm\":true}","{\"expected_version\":\"0\",\"confirm\":true}","{\"expected_version\":999999999999999999999,\"confirm\":true}"))
      mvc.perform(request(HttpMethod.DELETE,path()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
          .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("ranking.invalid_recovery_request"));
    for(String body:List.of("","null","{}","{\"removal_token\":null}","{\"removal_token\":\"bad\"}"))
      mvc.perform(request(HttpMethod.POST,path()+"/restore").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ranking.invalid_recovery_request"));
    verifyNoInteractions(port);
  }
  @Test void invalidPrincipalFlagsAndMissingIdentityFailClosed() throws Exception {
    for(var p:List.of(new Principal(null,true,true,true,true),new Principal(user,false,true,true,true),new Principal(user,true,false,true,true),new Principal(user,true,true,false,true),new Principal(user,true,true,true,false))){
      authenticate(p);mvc.perform(request(HttpMethod.GET,path()+"/removal-preview")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ranking.not_found"));}
    SecurityContextHolder.clearContext();mvc.perform(request(HttpMethod.GET,path()+"/removal-preview")).andExpect(status().isNotFound());verifyNoInteractions(port);
  }
  @Test void failureCodesAreStableWithoutDetailsOrAutomaticRetries() throws Exception {
    var failures=List.of(new StaleEntryRecoveryException(),new ExpiredEntryRecoveryException(),new ArchivedEntryRecoveryException(),new RankingEntriesDeniedException(),new org.springframework.dao.DataAccessResourceFailureException("private SQL"));
    var codes=List.of("ranking.entry_state_stale","ranking.entry_recovery_expired","ranking.archived_read_only","ranking.not_found","ranking.operation_failed");
    for(int i=0;i<failures.size();i++){
      reset(port);when(port.restore(rank,entry,user,token)).thenThrow(failures.get(i));
      mvc.perform(request(HttpMethod.POST,path()+"/restore").contentType(MediaType.APPLICATION_JSON).content("{\"removal_token\":\""+token+"\"}"))
          .andExpect(status().is(i<3?409:i==3?404:500)).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\""+codes.get(i)+"\"}",true));
      verify(port).restore(rank,entry,user,token);verifyNoMoreInteractions(port);
    }
  }
  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsNotExpired,boolean notLocked){super("synthetic","unused",enabled,notExpired,credentialsNotExpired,notLocked,List.of());userid=id;}
    public UUID getUserid(){return userid;}
  }
}
