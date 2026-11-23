package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationAddressUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/ranking-invitation-address")
public class RankingInvitationAddressController {
  private final ManageRankingInvitationAddressUseCase address;

  public RankingInvitationAddressController(ManageRankingInvitationAddressUseCase address) {
    this.address = address;
  }

  @GetMapping
  public ResponseEntity<AddressResponse> own(@AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new AddressResponse(address.get(actor(actorId, principal)).orElse(null)));
  }

  @PostMapping("/rotate")
  public ResponseEntity<AddressResponse> rotate(@AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new AddressResponse(address.rotate(actor(actorId, principal))));
  }

  private static RankingActor actor(UUID userId, UserDetails principal) {
    if (userId == null || principal == null) throw new RankingAccessDeniedException();
    return new RankingActor(userId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  public record AddressResponse(@JsonInclude(JsonInclude.Include.ALWAYS) String recipientCode) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }

  @ExceptionHandler(RankingInvitationAddressUnavailableException.class)
  public ResponseEntity<Map<String, String>> failed() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.invitation_address_unavailable"));
  }
}
