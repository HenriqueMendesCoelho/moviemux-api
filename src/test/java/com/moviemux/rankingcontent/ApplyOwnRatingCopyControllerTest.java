package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.ApplyOwnRatingCopyController;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.*;
import com.moviemux.rankingcontent.domain.usecase.impl.ApplyOwnRatingCopyUseCaseImpl;
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

class ApplyOwnRatingCopyControllerTest {
  private static final UUID USER=new UUID(0,1);
  private static final RatingCopyContext SOURCE=new RatingCopyContext(new UUID(0,2),new UUID(0,3),new UUID(0,4));
  private static final RatingCopyContext TARGET=new RatingCopyContext(new UUID(0,5),new UUID(0,6),new UUID(0,7));
  private static final Instant EXPIRY=Instant.parse("2026-11-01T00:00:00Z");
  private static final OpenedRatingCopyPreview OPENED=new OpenedRatingCopyPreview(new UUID(0,8),USER,
      new RatingCopyPair(SOURCE,TARGET),EXPIRY,new RatingCopySourceVersion(0,EXPIRY.minusSeconds(60),null));
  private static final String SUFFIX="/"+TARGET.rankingId()+"/entries/"+TARGET.entryId()+"/rounds/"+TARGET.roundId()+"/rating/me/copy-apply";
  private static final String PATH="/movies/collections"+SUFFIX, LEGACY="/rankings"+SUFFIX;
  private static final String TOKEN="A".repeat(263)+"_";
  private static final String BODY="{\"preview_token\":\""+TOKEN+"\"}";
  private final OpenOwnRatingCopyPreview opener=mock(OpenOwnRatingCopyPreview.class);
  private final ApplyOwnRatingCopy writes=mock(ApplyOwnRatingCopy.class);
  private MockMvc mvc;

  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mvc=MockMvcBuilders.standaloneSetup(new ApplyOwnRatingCopyController(new ApplyOwnRatingCopyUseCaseImpl(opener,writes,()->true)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void canonicalAndExactLegacyAliasReturnSame201WithoutEchoOrCallerIdentity() throws Exception {
    when(opener.open(TOKEN)).thenReturn(OPENED);
    when(writes.apply(USER,OPENED,true)).thenReturn(new OwnRoundRating(TARGET.roundId(),0));
    for(var path:List.of(PATH,LEGACY)){
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY).header("X-User-Id",UUID.randomUUID()))
          .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"round_id\":\""+TARGET.roundId()+"\",\"score\":0}",true));
      mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
    }
    verify(opener,times(2)).open(TOKEN);verify(writes,times(2)).apply(USER,OPENED,true);
    verifyNoMoreInteractions(opener,writes);
  }

  @Test void malformedBodyFieldPathQueryAndTokenShapeNeverReachCryptoOrStorage() throws Exception {
    for(var invalid:List.of("","null","{}","[]","{","{\"preview_token\":null}","{\"preview_token\":42}",
        "{\"preview_token\":true}",BODY.replace("}",",\"score\":9}"),BODY.replace("}",",\"preview_token\":\"bad\"}"),
        BODY.replace(TOKEN,TOKEN+"="),BODY.replace(TOKEN,TOKEN.substring(1)),BODY.replace(TOKEN,TOKEN.replace("_","+")),
        BODY+"{}",BODY+" ".repeat(2048)))
      expect(PATH,invalid,400,"ranking.invalid_rating_copy");
    expect(PATH.replace(TARGET.roundId().toString(),"1-1-1-1-1"),BODY,400,"ranking.invalid_rating_copy");
    expect(PATH+"?score=9",BODY,400,"ranking.invalid_rating_copy");
    verifyNoInteractions(opener,writes);
  }

  @Test void inactiveOrMissingPrincipalNeverOpensToken() throws Exception {
    for(var principal:List.of(new Principal(null,true,true,true,true),new Principal(USER,false,true,true,true),
        new Principal(USER,true,false,true,true),new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))){
      authenticate(principal);expect(PATH,BODY,404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();expect(PATH,BODY,404,"ranking.not_found");
    verifyNoInteractions(opener,writes);
  }

  @Test void malformedMaterialUnavailableCryptoAndPortErrorsReturnSafeEnvelopes() throws Exception {
    when(opener.open(TOKEN)).thenThrow(new InvalidRatingCopyException(),new RatingCopyUnavailableException());
    expect(PATH,BODY,400,"ranking.invalid_rating_copy");
    expect(PATH,BODY,503,"ranking.rating_copy_unavailable");
    verify(writes).requireTarget(USER,TARGET);verifyNoMoreInteractions(writes);
    reset(opener,writes);when(opener.open(TOKEN)).thenReturn(OPENED);
    for(var failure:List.of(new Failure(new RankingEntriesDeniedException(),404,"ranking.not_found"),
        new Failure(new StaleRatingCopyException(),409,"ranking.rating_copy_stale"),
        new Failure(new DuplicateOwnRoundRatingException(),409,"ranking.rating_exists"),
        new Failure(new RatingCopyUnavailableException(),503,"ranking.rating_copy_unavailable"),
        new Failure(new DataAccessResourceFailureException("private query"),500,"ranking.operation_failed"),
        new Failure(new TransactionSystemException("private transaction"),500,"ranking.operation_failed"))){
      reset(writes);when(writes.apply(USER,OPENED,true)).thenThrow(failure.error());
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
