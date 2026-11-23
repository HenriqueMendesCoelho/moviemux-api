package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.adapter.controller.SearchTmdbPickerController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.usecase.SearchTmdbPickerUseCase;
import java.time.LocalDate;
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

class SearchTmdbPickerControllerTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final String PATH="/rankings/"+RANK+"/entries/tmdb/search";
  private static final String BODY="{\"query\":\"Termo reservado\",\"page\":1,\"language\":\"pt-BR\"}";
  private final SearchTmdbPickerUseCase picker=mock(SearchTmdbPickerUseCase.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new SearchTmdbPickerController(picker))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void exposesOnlyAllowlistedPickerFieldsWithExplicitNullsAndUsesPrincipal() throws Exception {
    var item=new TmdbPickerMovie(42,"Título",null,LocalDate.of(2026,1,2),null);
    when(picker.search(ACTOR,RANK,"Termo reservado",1,"pt-BR")).thenReturn(new TmdbPickerPage(List.of(item),1,true));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).header("X-User-Id",UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("""
            {"items":[{"tmdb_id":42,"title":"Título","original_title":null,
            "release_date":"2026-01-02","poster_path":null}],"page":1,"has_more":true}
            """,true));
    verify(picker).search(ACTOR,RANK,"Termo reservado",1,"pt-BR");
    verifyNoMoreInteractions(picker);
  }

  @Test void rejectsNonExactOrCoercedJsonAndQueryStringBeforeUseCase() throws Exception {
    for(String invalid:List.of("","null","{}","[]","1","{",
        BODY.replace("1","1.0"),BODY.replace("1","1e0"),BODY.replace("1","\"1\""),BODY.replace("1","true"),
        BODY.replace("1","2147483648"),BODY.replace("1","{}"),BODY.replace("1","null"),
        BODY.replace("\"Termo reservado\"","7"),BODY.replace("\"pt-BR\"","null"),
        BODY.replace("}",",\"ranking_id\":\"foreign\"}"),
        BODY.replace("}",",\"query\":\"repetido\"}"),BODY+"{}"))
      expect(PATH,invalid,400,"ranking.invalid_tmdb_search");
    expect("/rankings/1-1-1-1-1/entries/tmdb/search",BODY,400,"ranking.invalid_tmdb_search");
    mvc.perform(post(PATH).queryParam("query","Termo reservado").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invalid_tmdb_search\"}",true));
    verifyNoInteractions(picker);
  }

  @Test void missingPrincipalAndEveryDisabledFlagAreDenied() throws Exception {
    when(picker.search(new RankingActor(USER,false),RANK,"Termo reservado",1,"pt-BR")).thenThrow(new RankingEntriesDeniedException());
    for(var principal:List.of(new Principal(USER,false,true,true,true),new Principal(USER,true,false,true,true),
        new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false),
        new Principal(null,true,true,true,true))){
      authenticate(principal);
      expect(PATH,BODY,404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(PATH,BODY,404,"ranking.not_found");
    verify(picker,times(4)).search(new RankingActor(USER,false),RANK,"Termo reservado",1,"pt-BR");
    verifyNoMoreInteractions(picker);
  }

  @Test void errorsNeverIncludeQueryOrRawProviderStatus() throws Exception {
    for(var test:List.of(new Failure(new InvalidTmdbSearchException(),400,"ranking.invalid_tmdb_search"),
        new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new ArchivedCatalogEntryException(),409,"ranking.archived_read_only"),
        new Failure(new TmdbImportUnavailableException(),503,"ranking.tmdb_import_unavailable"),
        new Failure(TmdbProviderFailureException.invalidResponse(),502,"ranking.tmdb_invalid_response"),
        new Failure(TmdbProviderFailureException.remoteStatus(404),503,"ranking.tmdb_unavailable"),
        new Failure(TmdbProviderFailureException.remoteStatus(429),503,"ranking.tmdb_unavailable"),
        new Failure(TmdbProviderFailureException.transport(),503,"ranking.tmdb_unavailable"),
        new Failure(new DataAccessResourceFailureException("private SQL"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private tx"),500,"ranking.operation_failed"))){
      reset(picker);
      when(picker.search(ACTOR,RANK,"Termo reservado",1,"pt-BR")).thenThrow(test.error());
      expect(PATH,BODY,test.status(),test.code());
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
  private record Failure(RuntimeException error,int status,String code) {}

  public static final class Principal extends User {
    private final UUID userid;
    Principal(UUID id,boolean enabled,boolean notExpired,boolean credentialsValid,boolean notLocked){
      super("synthetic","unused",enabled,notExpired,credentialsValid,notLocked,List.of());userid=id;
    }
    public UUID getUserid(){return userid;}
  }
}
