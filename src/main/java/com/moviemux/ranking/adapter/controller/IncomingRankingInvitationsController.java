package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.ListIncomingRankingInvitationsUseCase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping({"/movies/collections/invitations", "/ranking-invitations"})
public class IncomingRankingInvitationsController {
  private final ListIncomingRankingInvitationsUseCase inbox;

  public IncomingRankingInvitationsController(ListIncomingRankingInvitationsUseCase inbox) {
    this.inbox = inbox;
  }

  @GetMapping("/incoming")
  public ResponseEntity<PageResponse> list(@RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingInvitationNotFoundException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var page = inbox.list(actor, after, limit);
    var items = page.items().stream()
        .map(item -> new ItemResponse(item.inviteId(), item.rankingId(), item.rankingName(), item.expiresAt())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(items, page.nextCursor()));
  }

  public record ItemResponse(UUID inviteId, UUID rankingId,
      @JsonInclude(JsonInclude.Include.ALWAYS) String rankingName, OffsetDateTime expiresAt) {}
  public record PageResponse(List<ItemResponse> items, @JsonInclude(JsonInclude.Include.ALWAYS) UUID nextCursor) {}

  @ExceptionHandler({InvalidRankingAccessPageException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> invalidPage() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_page"); }

  @ExceptionHandler(RankingInvitationNotFoundException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.invitation_not_found"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
