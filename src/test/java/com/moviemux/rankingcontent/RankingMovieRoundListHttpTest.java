package com.moviemux.rankingcontent;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.rankingcontent.adapter.controller.RankingMovieRoundListController;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.usecase.RankingMovieRoundListUseCase;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
class RankingMovieRoundListHttpTest {
  @Test void metadataResponseAndErrorsAreNoStoreWithNoVotePayload() throws Exception {
    UUID actor=UUID.randomUUID(),rank=UUID.randomUUID(),entry=UUID.randomUUID();
    var useCase=mock(RankingMovieRoundListUseCase.class);
    var mvc=MockMvcBuilders.standaloneSetup(new RankingMovieRoundListController(useCase))
        .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
          public boolean supportsParameter(MethodParameter p) { return p.hasParameterAnnotation(AuthenticationPrincipal.class); }
          public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest r,WebDataBinderFactory b) {
            return p.getParameterType()==UUID.class?actor:User.withUsername("synthetic").password("synthetic").roles("ADMIN").build();
          }
        }).build();
    when(useCase.execute(any(),eq(rank),eq(entry),isNull(),eq(20)))
        .thenReturn(new RankingMovieRoundListPage(List.of(new RankingMovieRoundListItem(UUID.randomUUID(),1,null,Instant.EPOCH,null,null)),null));
    String path="/rankings/"+rank+"/entries/"+entry+"/rounds";
    mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.items[0].number").value(1)).andExpect(jsonPath("$.items[0].revealed").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.items[0].score").doesNotExist()).andExpect(jsonPath("$.items[0].authorId").doesNotExist())
        .andExpect(jsonPath("$.items[0].voteCount").doesNotExist()).andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    doThrow(new RankingAccessDeniedException()).when(useCase).execute(any(),eq(rank),eq(entry),isNull(),eq(20));
    mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(jsonPath("$.code").value("ranking.not_found"));
    for (RuntimeException failure : List.of(
        new org.springframework.dao.DataAccessResourceFailureException("private database diagnostic"),
        new org.springframework.transaction.CannotCreateTransactionException("private transaction diagnostic"))) {
      doThrow(failure).when(useCase).execute(any(),eq(rank),eq(entry),isNull(),eq(20));
      mvc.perform(get(path)).andExpect(status().isInternalServerError())
          .andExpect(header().string("Cache-Control","no-store"))
          .andExpect(content().json("{\"code\":\"ranking.operation_failed\"}", true));
    }
    mvc.perform(get(path).param("afterNumber","invalid")).andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control","no-store"));
  }
}
