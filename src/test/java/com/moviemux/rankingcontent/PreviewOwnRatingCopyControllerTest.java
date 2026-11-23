package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.adapter.controller.PreviewOwnRatingCopyController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult.Status;
import com.moviemux.rankingcontent.domain.usecase.PreviewOwnRatingCopyUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class PreviewOwnRatingCopyControllerTest {
  private static final UUID USER=new UUID(0,1);
  private static final RatingCopyContext SOURCE=new RatingCopyContext(new UUID(0,2),new UUID(0,3),new UUID(0,4));
  private static final RatingCopyContext DEST=new RatingCopyContext(new UUID(0,5),new UUID(0,6),new UUID(0,7));
  private static final RatingCopyPair PAIR=new RatingCopyPair(SOURCE,DEST);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final String BASE="/rankings/"+DEST.rankingId()+"/entries/"+DEST.entryId()+"/rounds/"+DEST.roundId()+"/rating/me";
  private static final String PATH=BASE+"/copy-preview";
  private static final String BODY="""
      {"source":{"ranking_id":"%s","entry_id":"%s","round_id":"%s"}}
      """.formatted(SOURCE.rankingId(),SOURCE.entryId(),SOURCE.roundId());
  private final PreviewOwnRatingCopyUseCase copies=mock(PreviewOwnRatingCopyUseCase.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new PreviewOwnRatingCopyController(copies))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void readyOnlyUsesPrincipalAndExactSourceDestinationScopes() throws Exception {
    when(copies.preview(ACTOR,PAIR)).thenReturn(new OwnRatingCopyPreviewResult(Status.READY,0,"opaque-synthetic",
        Instant.parse("2026-11-01T12:01:00Z")));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).header("X-Actor",UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("""
            {"status":"READY","source_score":0,"preview_token":"opaque-synthetic","expires_at":"2026-11-01T12:01:00Z"}
            """,true));
    verify(copies).preview(ACTOR,PAIR);
    verifyNoMoreInteractions(copies);
  }

  @Test void blockedFieldsAreExplicitlyNull() throws Exception {
    for (var state:List.of(Status.SOURCE_INELIGIBLE,Status.DESTINATION_EXISTS)) {
      when(copies.preview(ACTOR,PAIR)).thenReturn(new OwnRatingCopyPreviewResult(state,null,null,null));
      mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("""
              {"status":"%s","source_score":null,"preview_token":null,"expires_at":null}
              """.formatted(state),true));
    }
  }

  @Test void rejectsMissingMalformedDuplicateOversizeUnexpectedFieldsAndQueryBeforeUseCase() throws Exception {
    for (String bad:List.of("","{","null","[]","{\"source\":{}}","{\"source\":1}",
        BODY.replace("\""+SOURCE.roundId()+"\"","3"),BODY.replace(SOURCE.roundId().toString(),"1-1-1-1-1"),
        BODY.replaceFirst("\\{","{\"score\":0,"),BODY.replace("\"source\":","\"source\":null,\"source\":"),
        BODY.replace("\"round_id\":","\"round_id\":null,\"round_id\":"),
        BODY.replace("\"round_id\":","\"actor\":\"foreign\",\"round_id\":"),BODY+"{}",BODY+" ".repeat(1024))) {
      expect(PATH,bad,400,"ranking.invalid_rating_copy");
    }
    expect(PATH.replace(DEST.roundId().toString(),"1-1-1-1-1"),BODY,400,"ranking.invalid_rating_copy");
    mvc.perform(post(PATH).queryParam("token","private-token").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invalid_rating_copy\"}",true));
    verifyNoInteractions(copies);
  }

  @Test void authenticationFlagsFailClosedAndNoApplyOrGetRouteExists() throws Exception {
    when(copies.preview(new RankingActor(USER,false),PAIR)).thenThrow(new RankingEntriesDeniedException());
    for(var principal:List.of(new Principal(USER,false,true,true,true),new Principal(USER,true,false,true,true),
        new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false),
        new Principal(null,true,true,true,true))){
      authenticate(principal);
      expect(PATH,BODY,404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(PATH,BODY,404,"ranking.not_found");
    verify(copies,times(4)).preview(new RankingActor(USER,false),PAIR);
    mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());
    mvc.perform(post(BASE+"/copy-apply").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    verifyNoMoreInteractions(copies);
  }

  @Test void errorsAreOnlyRedactedCodesWithNoStore() throws Exception {
    for(var failure:List.of(
        new Failure(new InvalidRatingCopyException(),400,"ranking.invalid_rating_copy"),
        new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new RatingCopyUnavailableException(),503,"ranking.rating_copy_unavailable"),
        new Failure(new DataAccessResourceFailureException("private SQL"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private transaction"),500,"ranking.operation_failed"))){
      reset(copies);when(copies.preview(ACTOR,PAIR)).thenThrow(failure.error());
      expect(PATH,BODY,failure.status(),failure.code());
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
