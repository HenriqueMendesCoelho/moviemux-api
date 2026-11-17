package com.moviemux.ranking.adapter.controller;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.model.RankingAccessView;
import java.util.List;
import com.moviemux.ranking.domain.usecase.ListRankingAccessUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/rankings")
public class RankingAccessListController {
  private final ListRankingAccessUseCase useCase;

  public RankingAccessListController(ListRankingAccessUseCase useCase) { this.useCase = useCase; }

  @GetMapping("/access")
  public ResponseEntity<PageResponse> list(
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit,
      @AuthenticationPrincipal(expression = "userid") UUID actorUserId,
      @AuthenticationPrincipal UserDetails principal) {
    if (principal == null || actorUserId == null) throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorUserId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var page = useCase.execute(actor, after, limit);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new PageResponse(page.items(), page.nextCursor()));
  }

  public record PageResponse(List<RankingAccessView> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID nextCursor) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }

  @ExceptionHandler({InvalidRankingAccessPageException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> badPage() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.invalid_page"));
  }
}
