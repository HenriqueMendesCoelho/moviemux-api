package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.moviemux.ranking.domain.exception.DuplicateRankingInvitationException;
import com.moviemux.ranking.domain.exception.InvalidRankingInvitationException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationsUseCase;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/rankings/{rankingId}/invitations")
public class RankingInvitationController {
  private final ManageRankingInvitationsUseCase invitations;

  public RankingInvitationController(ManageRankingInvitationsUseCase invitations) {
    this.invitations = invitations;
  }

  @PostMapping
  public ResponseEntity<IssuedInvitationResponse> issue(@PathVariable UUID rankingId, @RequestBody JsonNode body,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    if (body == null || !body.isObject() || body.size() != 1 || !body.path("recipient_code").isTextual())
      throw new InvalidRankingInvitationException();
    var issued = invitations.issue(actor, rankingId, body.get("recipient_code").textValue());
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
        .body(new IssuedInvitationResponse(issued.inviteId(), issued.expiresAt()));
  }

  @PostMapping("/{inviteId}/accept")
  public ResponseEntity<Void> accept(@PathVariable UUID rankingId, @PathVariable UUID inviteId,
      @RequestBody(required = false) byte[] body,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (body != null && body.length != 0) throw new InvalidRankingInvitationException();
    invitations.accept(actor(actorId, principal), rankingId, inviteId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @DeleteMapping("/{inviteId}")
  public ResponseEntity<Void> revoke(@PathVariable UUID rankingId, @PathVariable UUID inviteId,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    invitations.revoke(actor(actorId, principal), rankingId, inviteId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private static RankingActor actor(UUID userId, UserDetails principal) {
    if (userId == null || principal == null) throw new RankingInvitationNotFoundException();
    return new RankingActor(userId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  public record IssuedInvitationResponse(UUID inviteId, OffsetDateTime expiresAt) {}

  @ExceptionHandler({InvalidRankingInvitationException.class, HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> invalid() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_invitation");
  }

  @ExceptionHandler(DuplicateRankingInvitationException.class)
  public ResponseEntity<Map<String, String>> duplicate() {
    return error(HttpStatus.CONFLICT, "ranking.invitation_exists");
  }

  @ExceptionHandler(RankingInvitationNotFoundException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return error(HttpStatus.NOT_FOUND, "ranking.invitation_not_found");
  }

  @ExceptionHandler(RankingInvitationAddressUnavailableException.class)
  public ResponseEntity<Map<String, String>> addressUnavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.invitation_address_unavailable");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
