package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moviemux.ranking.adapter.controller.RankingPersistenceErrorAdvice;
import com.moviemux.ranking.adapter.controller.RankingVisibilitySettingsController;
import com.moviemux.ranking.domain.exception.InvalidVisibilitySettingsException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.RankingVisibilitySettings;
import com.moviemux.ranking.domain.usecase.impl.ManageRankingVisibilityUseCaseImpl;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;

class RankingVisibilitySettingsControllerTest {
  private static final UUID RANK = UUID.fromString("12345678-1234-1234-1234-123456789abc"), USER = new UUID(0, 2);
  private static final String PATH = "/rankings/"+RANK+"/settings/visibility";
  private static final String PUBLIC = "{\"visibility\":\"PUBLIC\"}";
  private final RankingVisibilitySettings settings = mock(RankingVisibilitySettings.class);
  private final RankingVisibilitySettingsController controller =
      new RankingVisibilitySettingsController(new ManageRankingVisibilityUseCaseImpl(settings));
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RankingPersistenceErrorAdvice())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    authenticate(new Principal(USER,true,true,true,true));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void getAndPutReturnOneExactFieldAndUseOnlyCurrentPrincipal() throws Exception {
    when(settings.current(RANK,USER)).thenReturn(RankingVisibility.PRIVATE);
    when(settings.set(RANK,USER,RankingVisibility.PUBLIC)).thenReturn(RankingVisibility.PUBLIC);
    mvc.perform(request(HttpMethod.GET,PATH).header("X-User-Id",UUID.randomUUID()))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json("{\"visibility\":\"PRIVATE\"}",true));
    mvc.perform(request(HttpMethod.PUT,PATH).contentType(MediaType.APPLICATION_JSON).content(PUBLIC)
        .header("X-Owner",UUID.randomUUID()).header("X-Role","ADM"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(content().json(PUBLIC,true));
    verify(settings).current(RANK,USER);verify(settings).set(RANK,USER,RankingVisibility.PUBLIC);
    verifyNoMoreInteractions(settings);
  }

  @Test void malformedOrExtraInputNeverUsesStorage() throws Exception {
    for(var body : List.of("","{}","null","[]","{\"visibility\":null}","{\"visibility\":0}","{\"visibility\":true}",
        "{\"visibility\":\"public\"}","{\"visibility\":\"PUBLIC \"}","{\"visibility\":\"UNLISTED\"}",
        "{\"Visibility\":\"PUBLIC\"}","{\"visibility\":\"PUBLIC\",\"owner\":true}",
        "{\"visibility\":\"PRIVATE\",\"visibility\":\"PUBLIC\"}",PUBLIC+" {}",PUBLIC+" ".repeat(1024)))
      expect(HttpMethod.PUT,PATH,body,400,"ranking.invalid_visibility_settings");
    expect(HttpMethod.PUT,PATH+"?actor=other",PUBLIC,400,"ranking.invalid_visibility_settings");
    expect(HttpMethod.GET,PATH+"?visibility=PUBLIC","",400,"ranking.invalid_visibility_settings");
    expect(HttpMethod.GET,PATH,"{}",400,"ranking.invalid_visibility_settings");
    verifyNoInteractions(settings);
  }

  @Test void unreadableOrUnknownLengthGetUsesOneByteAndNeverReachesStorage() throws Exception {
    var request=mock(HttpServletRequest.class);
    var stream=mock(ServletInputStream.class);
    var principal=new Principal(USER,true,true,true,true);
    when(request.getContentLengthLong()).thenReturn(-1L);
    when(request.getInputStream()).thenReturn(stream);
    when(stream.read()).thenReturn(0).thenThrow(new IOException("private transport body"));
    for(int i=0;i<2;i++)
      assertThatThrownBy(()->controller.current(RANK.toString(),request,USER,principal))
          .isInstanceOf(InvalidVisibilitySettingsException.class).hasNoCause();
    verify(stream,times(2)).read();verifyNoMoreInteractions(stream);verifyNoInteractions(settings);
  }

  @Test void malformedScopeAndInvalidAccountRemainUniform404ForBothMethods() throws Exception {
    for(String bad : List.of("bad", "a-b-c-d-e",RANK+"0","81234567-ABCD-1234-ABCD-0987654321FF")) {
      var path="/rankings/"+bad+"/settings/visibility";
      expect(HttpMethod.GET,path,"",404,"ranking.not_found");
      expect(HttpMethod.PUT,path,PUBLIC,404,"ranking.not_found");
    }
    for(var principal : List.of(new Principal(null,true,true,true,true),new Principal(USER,false,true,true,true),
        new Principal(USER,true,false,true,true),new Principal(USER,true,true,false,true),new Principal(USER,true,true,true,false))) {
      authenticate(principal);
      expect(HttpMethod.GET,PATH,"",404,"ranking.not_found");
      expect(HttpMethod.PUT,PATH,PUBLIC,404,"ranking.not_found");
    }
    SecurityContextHolder.clearContext();
    expect(HttpMethod.GET,PATH,"",404,"ranking.not_found");
    expect(HttpMethod.PUT,PATH,PUBLIC,404,"ranking.not_found");
    verifyNoInteractions(settings);
  }

  @Test void deniedAndPersistenceFailureStatusesAreRedactedAndNoStore() throws Exception {
    when(settings.current(RANK,USER)).thenThrow(new RankingAccessDeniedException(),
        new DataAccessResourceFailureException("private SQL"));
    when(settings.set(RANK,USER,RankingVisibility.PUBLIC)).thenThrow(new RankingAccessDeniedException(),
        new TransactionSystemException("private transaction"));
    expect(HttpMethod.GET,PATH,"",404,"ranking.not_found");
    expect(HttpMethod.PUT,PATH,PUBLIC,404,"ranking.not_found");
    expect(HttpMethod.GET,PATH,"",500,"ranking.operation_failed");
    expect(HttpMethod.PUT,PATH,PUBLIC,500,"ranking.operation_failed");
  }

  private void expect(HttpMethod method,String path,String body,int status,String code) throws Exception {
    mvc.perform(request(method,path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
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
