package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.moviemux.ranking.adapter.controller.RankingAccessController;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.usecase.GetRankingAccessUseCase;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

class RankingAccessControllerTest {
  private final UUID ranking = UUID.randomUUID(), actor = UUID.randomUUID();

  @Test void nullPrincipalOrIdentityCannotReachTheUseCase() {
    var useCase = mock(GetRankingAccessUseCase.class);
    var controller = new RankingAccessController(useCase);
    assertThatThrownBy(() -> controller.get(ranking, actor, null)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> controller.get(ranking, null, enabledPrincipal())).isInstanceOf(RankingAccessDeniedException.class);
    verifyNoInteractions(useCase);
  }

  @Test void everyDisabledPrincipalStateIsPropagatedAsIneligible() {
    for (int state=0;state<4;state++) {
      var principal = enabledPrincipal();
      if(state==0) when(principal.isEnabled()).thenReturn(false);
      if(state==1) when(principal.isAccountNonLocked()).thenReturn(false);
      if(state==2) when(principal.isAccountNonExpired()).thenReturn(false);
      if(state==3) when(principal.isCredentialsNonExpired()).thenReturn(false);
      var useCase = mock(GetRankingAccessUseCase.class);
      when(useCase.execute(new RankingActor(actor, false), ranking)).thenThrow(new RankingAccessDeniedException());
      assertThatThrownBy(() -> new RankingAccessController(useCase).get(ranking, actor, principal))
          .isInstanceOf(RankingAccessDeniedException.class);
      verify(useCase).execute(new RankingActor(actor, false), ranking);
    }
  }

  @Test void enabledPrincipalReturnsOnlyExplicitViewAndNoStore() {
    var useCase = mock(GetRankingAccessUseCase.class);
    when(useCase.execute(new RankingActor(actor,true),ranking))
        .thenReturn(new RankingAccessView(ranking, Set.of(RankingCapability.READ)));
    var response = new RankingAccessController(useCase).get(ranking,actor,enabledPrincipal());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody().rankingId()).isEqualTo(ranking);
    assertThat(response.getBody().capabilities()).containsExactly(RankingCapability.READ);
  }

  @Test void scopeDenialIsGenericAndNotCacheable() {
    var response = new RankingAccessController(mock(GetRankingAccessUseCase.class)).notFound();
    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).containsExactly(entry("code","ranking.not_found"));
  }

  private UserDetails enabledPrincipal() {
    var principal = mock(UserDetails.class);
    when(principal.isEnabled()).thenReturn(true);
    when(principal.isAccountNonLocked()).thenReturn(true);
    when(principal.isAccountNonExpired()).thenReturn(true);
    when(principal.isCredentialsNonExpired()).thenReturn(true);
    return principal;
  }
}
