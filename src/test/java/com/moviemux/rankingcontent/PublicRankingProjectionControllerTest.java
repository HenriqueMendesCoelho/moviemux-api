package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.moviemux.rankingcontent.adapter.controller.PublicRankingProjectionController;
import com.moviemux.rankingcontent.domain.model.PublicRankingEntry;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import com.moviemux.rankingcontent.domain.port.ReadPublicRankingProjection;
import com.moviemux.rankingcontent.domain.usecase.impl.ReadPublicRankingUseCaseImpl;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class PublicRankingProjectionControllerTest {
  private static final UUID RANK=new UUID(0,1), ENTRY=new UUID(0,2), NEXT=new UUID(0,3);
  private static final String PATH="/public/rankings/"+RANK, ENTRIES=PATH+"/entries";
  private final ReadPublicRankingProjection port=mock(ReadPublicRankingProjection.class);
  private final PublicRankingProjectionController controller=new PublicRankingProjectionController(new ReadPublicRankingUseCaseImpl(port));
  private MockMvc mvc;

  @BeforeEach void setup() {
    var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    mvc=MockMvcBuilders.standaloneSetup(controller)
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
  }

  @Test void summaryIsExactAndInvariantAcrossHeadersAndCookies() throws Exception {
    when(port.summary(RANK)).thenReturn(Optional.of(new PublicRankingSummary(RANK,null)));
    String plain=mvc.perform(get(PATH)).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
    mvc.perform(get(PATH).cookie(new Cookie("session","not-a-credential")).header("Authorization","Bearer invalid")
        .header("Accept-Language","en").header("X-Actor-Id",new UUID(0,99)))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().string(plain))
        .andExpect(content().json("{\"ranking_id\":\""+RANK+"\",\"ranking_name\":null}",true));
    verify(port,times(2)).summary(RANK);
    verifyNoMoreInteractions(port);
  }

  @Test void entriesHaveOnlyAllowedFieldsExactIntegersAndExplicitNulls() throws Exception {
    BigInteger total=BigInteger.ONE.shiftLeft(128).negate(), votes=BigInteger.ONE.shiftLeft(96);
    when(port.entries(RANK,null,21)).thenReturn(Optional.of(List.of(new PublicRankingEntry(ENTRY,null,total,votes))));
    var result=mvc.perform(get(ENTRIES)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andReturn().getResponse();
    var json=new ObjectMapper().readTree(result.getContentAsString());
    var item=json.path("items").get(0);
    assertThat(json.size()).isEqualTo(2);
    assertThat(json.get("next_cursor").isNull()).isTrue();
    assertThat(item.size()).isEqualTo(4);
    assertThat(item.path("entry_id").asText()).isEqualTo(ENTRY.toString());
    assertThat(item.get("title").isNull()).isTrue();
    assertThat(item.get("total").bigIntegerValue()).isEqualTo(total);
    assertThat(item.get("votes").bigIntegerValue()).isEqualTo(votes);
    verify(port).entries(RANK,null,21);
    verifyNoMoreInteractions(port);
  }

  @Test void acceptsOnlyAfterAndLimitOnEntriesAndCursorIsLastExposedItem() throws Exception {
    when(port.entries(RANK,RANK,2)).thenReturn(Optional.of(List.of(
        new PublicRankingEntry(ENTRY,"Film",BigInteger.ZERO,BigInteger.ZERO),
        new PublicRankingEntry(NEXT,"Lookahead",BigInteger.ZERO,BigInteger.ZERO))));
    mvc.perform(get(ENTRIES).queryParam("limit","1").queryParam("after",RANK.toString()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("""
            {"items":[{"entry_id":"%s","title":"Film","total":0,"votes":0}],"next_cursor":"%s"}
            """.formatted(ENTRY,ENTRY),true));
    verify(port).entries(RANK,RANK,2);
    verifyNoMoreInteractions(port);
  }

  @Test void malformedScopesNeverProbeStorage() throws Exception {
    for(String malformed:List.of("bad","1-1-1-1-1",RANK+"0","12345678-ABCD-1234-ABCD-123456789ABC")) {
      expect("/public/rankings/"+malformed,404,"ranking.not_found");
      expect("/public/rankings/"+malformed+"/entries?limit=bad",404,"ranking.not_found");
    }
    verifyNoInteractions(port);
  }

  @Test void invalidOptionsAndBodiesCannotDistinguishUnavailableScope() throws Exception {
    for(boolean live : new boolean[]{false,true}) {
      reset(port);
      when(port.summary(RANK)).thenReturn(live?Optional.of(new PublicRankingSummary(RANK,null)):Optional.empty());
      int status=live?400:404;
      String code=live?"ranking.invalid_page":"ranking.not_found";
      for(String path:List.of(PATH+"?limit=20",ENTRIES+"?limit=bad",ENTRIES+"?limit=1&limit=2",
          ENTRIES+"?after="+RANK+"&after="+ENTRY,ENTRIES+"?after=1-1-1-1-1",ENTRIES+"?limit=0",
          ENTRIES+"?limit=51",ENTRIES+"?limit=2147483648",ENTRIES+"?limit=1.0",ENTRIES+"?limit=1&",
          ENTRIES+"?actor=caller",ENTRIES+"?after=",ENTRIES+"?limit=1&arbitrary="+("x".repeat(64)))) expect(path,status,code);
      for(String path:List.of(PATH,ENTRIES)) mvc.perform(get(path).content("{}"))
          .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\""+code+"\"}",true));
      verify(port,times(15)).summary(RANK);
      verifyNoMoreInteractions(port);
    }
  }

  @Test void unknownLengthReadsAtMostOneByteAndIoFailureStillHasScopeGate() throws Exception {
    when(port.summary(RANK)).thenReturn(Optional.of(new PublicRankingSummary(RANK,null)));
    var request=mock(HttpServletRequest.class);
    var stream=mock(ServletInputStream.class);
    when(request.getContentLengthLong()).thenReturn(-1L);
    when(request.getInputStream()).thenReturn(stream);
    when(stream.read()).thenReturn(0).thenThrow(new IOException("private transport"));
    for(int i=0;i<2;i++) {
      org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.entries(RANK.toString(),request))
          .isInstanceOf(com.moviemux.rankingcontent.domain.exception.InvalidPublicRankingPageException.class).hasNoCause();
    }
    verify(stream,times(2)).read();verifyNoMoreInteractions(stream);
    verify(port,times(2)).summary(RANK);verifyNoMoreInteractions(port);
  }

  @Test void systemErrorsAreRedactedAndNoStore() throws Exception {
    when(port.summary(RANK)).thenThrow(new DataAccessResourceFailureException("private SQL"));
    expect(PATH,500,"ranking.operation_failed");
    when(port.entries(RANK,null,21)).thenThrow(new TransactionSystemException("private transaction"));
    expect(ENTRIES,500,"ranking.operation_failed");
  }

  private void expect(String path,int status,String code) throws Exception {
    int query=path.indexOf('?');
    var request=get(query<0?path:path.substring(0,query));
    if(query>=0) request.with(raw->{
      raw.setQueryString(path.substring(query+1));
      return raw;
    });
    mvc.perform(request).andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"code\":\""+code+"\"}",true));
  }
}
