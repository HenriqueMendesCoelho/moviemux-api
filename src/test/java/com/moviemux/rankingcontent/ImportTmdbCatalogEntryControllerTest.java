package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.adapter.controller.ImportTmdbCatalogEntryController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.usecase.ImportTmdbCatalogEntryUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class ImportTmdbCatalogEntryControllerTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final String PATH="/rankings/"+RANK+"/entries/tmdb";
  private final ImportTmdbCatalogEntryUseCase useCase=mock(ImportTmdbCatalogEntryUseCase.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mvc=MockMvcBuilders.standaloneSetup(new ImportTmdbCatalogEntryController(useCase))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void acceptedBoundariesReturnOnlyEntryIdWithNoStoreAndUsePrincipal() throws Exception {
    long max=9_007_199_254_740_991L;
    when(useCase.execute(ACTOR,RANK,1)).thenReturn(new AddedCatalogEntry(ENTRY,true));
    when(useCase.execute(ACTOR,RANK,max)).thenReturn(new AddedCatalogEntry(ENTRY,false));
    for (long id:new long[]{1,max}) {
      mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"tmdb_id\":"+id+"}")
          .header("X-User-Id", UUID.randomUUID()).queryParam("actor",UUID.randomUUID().toString()))
          .andExpect(status().is(id==1?201:200)).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"entry_id\":\""+ENTRY+"\"}",true));
      verify(useCase).execute(ACTOR,RANK,id);
    }
    verifyNoMoreInteractions(useCase);
  }

  @Test void malformedNonExactOrCoercedBodiesNeverReachTheUseCase() throws Exception {
    for(String body:List.of("", "null","{}","{","1","[]","{\"tmdb_id\":null}","{\"tmdbId\":1}",
        "{\"tmdb_id\":0}","{\"tmdb_id\":-1}","{\"tmdb_id\":9007199254740992}","{\"tmdb_id\":9223372036854775808}",
        "{\"tmdb_id\":1.0}","{\"tmdb_id\":1e0}","{\"tmdb_id\":true}","{\"tmdb_id\":\"1\"}",
        "{\"tmdb_id\":[]}","{\"tmdb_id\":{}}","{\"tmdb_id\":1,\"movie_id\":\"foreign\"}",
        "{\"tmdb_id\":1,\"tmdb_id\":1}","{\"tmdb_id\":1} {}")) {
      expect(PATH,body,400,"ranking.invalid_tmdb_id");
    }
    verifyNoInteractions(useCase);
  }

  @Test void malformedRankAndMissingIdentityAreGenericNotFound() throws Exception {
    expect("/rankings/1-1-1-1-1/entries/tmdb","{\"tmdb_id\":1}",404,"ranking.not_found");
    authenticate(new Principal(null,true,true,true,true));
    expect(PATH,"{\"tmdb_id\":1}",404,"ranking.not_found");
    SecurityContextHolder.clearContext();
    expect(PATH,"{\"tmdb_id\":1}",404,"ranking.not_found");
    verifyNoInteractions(useCase);
  }

  @Test void eachCurrentAccountFlagReachesUseCaseOnlyAsDisabled() throws Exception {
    when(useCase.execute(new RankingActor(USER,false),RANK,1)).thenThrow(new RankingEntriesDeniedException());
    for (var principal:List.of(new Principal(USER,false,true,true,true),new Principal(USER,true,false,true,true),
        new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))) {
      authenticate(principal);
      expect(PATH,"{\"tmdb_id\":1}",404,"ranking.not_found");
    }
    verify(useCase,times(4)).execute(new RankingActor(USER,false),RANK,1);
    verifyNoMoreInteractions(useCase);
  }

  @Test void failureCategoriesMapToExactRedactedCodesWithNoStore() throws Exception {
    for(var test:List.of(new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new ArchivedCatalogEntryException(),409,"ranking.archived_read_only"),
        new Failure(new ExpiredEntryRecoveryException(),409,"ranking.entry_recovery_expired"),
        new Failure(new CatalogIdentityConflictException(),409,"ranking.catalog_identity_conflict"),
        new Failure(new TmdbImportUnavailableException(),503,"ranking.tmdb_import_unavailable"),
        new Failure(new InvalidTmdbIdException(),400,"ranking.invalid_tmdb_id"),
        new Failure(TmdbProviderFailureException.remoteStatus(404),404,"ranking.tmdb_not_found"),
        new Failure(TmdbProviderFailureException.remoteStatus(429),503,"ranking.tmdb_unavailable"),
        new Failure(TmdbProviderFailureException.remoteStatus(500),503,"ranking.tmdb_unavailable"),
        new Failure(TmdbProviderFailureException.transport(),503,"ranking.tmdb_unavailable"),
        new Failure(TmdbProviderFailureException.invalidResponse(),502,"ranking.tmdb_invalid_response"),
        new Failure(new DataAccessResourceFailureException("private SQL"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private tx"),500,"ranking.operation_failed"))) {
      reset(useCase);
      when(useCase.execute(ACTOR,RANK,1)).thenThrow(test.failure());
      expect(PATH,"{\"tmdb_id\":1}",test.status(),test.code());
      verify(useCase).execute(ACTOR,RANK,1);
      verifyNoMoreInteractions(useCase);
    }
  }

  private void expect(String path,String body,int status,String code) throws Exception {
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
  private void authenticate(Principal principal){
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }
  private record Failure(RuntimeException failure,int status,String code) {}

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsValid,boolean notLocked){
      super("synthetic","unused",enabled,notExpired,credentialsValid,notLocked,List.of());userid=id;
    }
    public UUID getUserid(){return userid;}
  }
}
