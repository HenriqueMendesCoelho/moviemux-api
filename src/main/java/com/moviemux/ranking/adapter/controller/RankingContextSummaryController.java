package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingAdmission;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.usecase.GetRankingSummaryUseCase;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rankings")
public class RankingContextSummaryController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final GetRankingSummaryUseCase summaries;

  public RankingContextSummaryController(GetRankingSummaryUseCase summaries) {
    this.summaries = summaries;
  }

  @GetMapping("/{rankingId}")
  public ResponseEntity<ContextResponse> get(@PathVariable String rankingId,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null || rankingId == null || !UUID_PATTERN.matcher(rankingId).matches())
      throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result = summaries.get(actor, UUID.fromString(rankingId));
    var summary = result.summary();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new ContextResponse(summary.rankingId(), summary.name(), summary.visibility(),
            summary.admission(), summary.capabilities(), result.canShareOpenLink()));
  }

  public record ContextResponse(UUID rankingId, @JsonInclude(JsonInclude.Include.ALWAYS) String name,
      RankingVisibility visibility, RankingAdmission admission, Set<RankingCapability> capabilities,
      @JsonInclude(JsonInclude.Include.ALWAYS) boolean canShareOpenLink) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }
}
