package com.moviemux.ranking.adapter.controller;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.LeaveRankingUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections", "/rankings"})
public class LeaveRankingController {
  private final LeaveRankingUseCase useCase;
  public LeaveRankingController(LeaveRankingUseCase useCase) { this.useCase = useCase; }

  @DeleteMapping("/{rankingId}/membership/self")
  public ResponseEntity<Void> leave(@PathVariable UUID rankingId,
      @AuthenticationPrincipal(expression = "userid") UUID actorUserId,
      @AuthenticationPrincipal UserDetails principal) {
    if (principal == null || actorUserId == null) throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorUserId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    useCase.execute(actor, rankingId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }

  @ExceptionHandler(RankingOwnerExitRequiresDestinationException.class)
  public ResponseEntity<Map<String, String>> ownerRequiresDestination() {
    return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.owner_exit_requires_destination"));
  }
}
