package com.moviemux.ranking.adapter.controller;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.usecase.GetRankingAccessUseCase;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections", "/rankings"})
public class RankingAccessController {
  private final GetRankingAccessUseCase useCase;

  public RankingAccessController(GetRankingAccessUseCase useCase) { this.useCase = useCase; }

  @GetMapping("/{rankingId}/access")
  public ResponseEntity<AccessResponse> get(@PathVariable UUID rankingId,
      @AuthenticationPrincipal(expression = "userid") UUID actorUserId,
      @AuthenticationPrincipal UserDetails principal) {
    if (principal == null || actorUserId == null) throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorUserId, principal.isEnabled()
        && principal.isAccountNonLocked() && principal.isAccountNonExpired()
        && principal.isCredentialsNonExpired());
    var view = useCase.execute(actor, rankingId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new AccessResponse(view.rankingId(), view.capabilities()));
  }

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }

  public record AccessResponse(UUID rankingId, Set<RankingCapability> capabilities) {}
}
