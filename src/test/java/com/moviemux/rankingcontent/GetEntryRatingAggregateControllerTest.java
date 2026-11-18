package com.moviemux.rankingcontent;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.moviemux.rankingcontent.adapter.controller.GetEntryRatingAggregateController;
import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.CurrentEntryRatingAggregate;
import com.moviemux.rankingcontent.domain.usecase.impl.GetEntryRatingAggregateUseCaseImpl;
import java.util.*;
import java.math.BigInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GetEntryRatingAggregateControllerTest {
  private final UUID rank=UUID.randomUUID(),entry=UUID.randomUUID(),user=UUID.randomUUID();
  private final CurrentEntryRatingAggregate port=mock(CurrentEntryRatingAggregate.class);
  private MockMvc mvc;
  private String path(){return "/rankings/"+rank+"/entries/"+entry+"/ratings/aggregate";}
  @BeforeEach void setup(){
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(new GetEntryRatingAggregateController(new GetEntryRatingAggregateUseCaseImpl(port)))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    authenticate(new Principal(user,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  private void authenticate(Principal principal){
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal,null,"ROLE_ADM"));
  }
  @Test void exactLargeSignedTotalsAndPartialHaveOnlyAllowlistedFields() throws Exception {
    for(boolean partial:List.of(false,true)) {
      for(BigInteger total:List.of(BigInteger.ZERO,BigInteger.TEN,BigInteger.ONE.shiftLeft(120),BigInteger.ONE.shiftLeft(120).negate())) {
        BigInteger votes=total.signum()==0?BigInteger.ZERO:BigInteger.ONE.shiftLeft(100);
        when(port.find(rank,entry,user)).thenReturn(Optional.of(new EntryRatingAggregateView(entry,total,votes,partial)));
        var response=mvc.perform(get(path())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        var json=new ObjectMapper().readTree(response.getContentAsString());
        org.assertj.core.api.Assertions.assertThat(json.size()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(json.get("entry_id").asText()).isEqualTo(entry.toString());
        org.assertj.core.api.Assertions.assertThat(json.get("total").bigIntegerValue()).isEqualTo(total);
        org.assertj.core.api.Assertions.assertThat(json.get("votes").bigIntegerValue()).isEqualTo(votes);
        org.assertj.core.api.Assertions.assertThat(json.get("partial").booleanValue()).isEqualTo(partial);
      }
    }
  }
  @Test void forgedIdentityHeadersAndQueryNeverReplacePrincipal() throws Exception {
    when(port.find(rank,entry,user)).thenReturn(Optional.of(new EntryRatingAggregateView(entry,BigInteger.valueOf(7),BigInteger.ONE,true)));
    mvc.perform(get(path()).header("X-User-Id",UUID.randomUUID())
        .param("user_id",UUID.randomUUID().toString()).param("roles","ADM"))
        .andExpect(status().isOk()).andExpect(content().json("{\"entry_id\":\""+entry+"\",\"total\":7,\"votes\":1,\"partial\":true}",true));
    verify(port).find(rank,entry,user);verifyNoMoreInteractions(port);
  }
  @Test void missingPrincipalAndEachInvalidFlagPreventReads() throws Exception {
    for(var principal:List.of(new Principal(user,false,true,true,true),new Principal(user,true,false,true,true),
        new Principal(user,true,true,false,true),new Principal(user,true,true,true,false),new Principal(null,true,true,true,true))) {
      authenticate(principal);expectError(404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();expectError(404,"ranking.not_found");verifyNoInteractions(port);
  }
  @Test void missingAndInaccessibleAreUniformAndDatabaseErrorsAreSanitized() throws Exception {
    when(port.find(rank,entry,user)).thenReturn(Optional.empty());
    expectError(404,"ranking.not_found");
    for(RuntimeException failure:List.of(new org.springframework.dao.DataAccessResourceFailureException("private SQL"),
        new org.springframework.transaction.TransactionSystemException("private tx"))) {
      reset(port);when(port.find(rank,entry,user)).thenThrow(failure);expectError(500,"ranking.operation_failed");
    }
  }
  @Test void malformedPathDoesNotReachPort() throws Exception {
    mvc.perform(get("/rankings/bad/entries/"+entry+"/ratings/aggregate"))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\"ranking.invalid_rating_aggregate\"}",true));
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
