package com.moviemux.ranking.adapter.controller;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.usecase.ReplaceMemberCapabilitiesUseCase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/rankings")
public class ReplaceMemberCapabilitiesController {
  private final ReplaceMemberCapabilitiesUseCase useCase;
  public ReplaceMemberCapabilitiesController(ReplaceMemberCapabilitiesUseCase useCase) { this.useCase = useCase; }

  @PutMapping("/{rankingId}/members/{memberId}/capabilities")
  public ResponseEntity<Void> replace(@PathVariable UUID rankingId, @PathVariable UUID memberId,
      @RequestBody CapabilitiesRequest body,
      @AuthenticationPrincipal(expression = "userid") UUID actorUserId,
      @AuthenticationPrincipal UserDetails principal) {
    if (principal == null || actorUserId == null) throw new RankingAccessDeniedException();
    // Technical payload bound before duplicate normalization; not a product/member quota.
    if (body == null || body.capabilities() == null || body.capabilities().size() > 100)
      throw new InvalidRankingCapabilitiesException();
    var capabilities = EnumSet.noneOf(RankingCapability.class);
    // Duplicate known values normalize to a set. Unknown, null, or non-string values are invalid.
    for (Object value : body.capabilities()) {
      if (!(value instanceof String name)) throw new InvalidRankingCapabilitiesException();
      try { capabilities.add(RankingCapability.valueOf(name)); }
      catch (IllegalArgumentException invalid) { throw new InvalidRankingCapabilitiesException(); }
    }
    var actor = new RankingActor(actorUserId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    useCase.execute(actor, rankingId, memberId, capabilities);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  public record CapabilitiesRequest(List<Object> capabilities) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }
  @ExceptionHandler(RankingArchivedReadOnlyException.class)
  public ResponseEntity<Map<String,String>> archived() { return error(HttpStatus.CONFLICT, "ranking.archived_read_only"); }
  @ExceptionHandler(RankingOwnerCapabilitiesImmutableException.class)
  public ResponseEntity<Map<String,String>> immutableOwner() { return error(HttpStatus.CONFLICT, "ranking.owner_capabilities_immutable"); }
  @ExceptionHandler({InvalidRankingCapabilitiesException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> invalidCapabilities() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_capabilities"); }
  private ResponseEntity<Map<String,String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
