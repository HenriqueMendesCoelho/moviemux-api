package com.moviemux.ranking.adapter.controller;

import com.moviemux.ranking.domain.exception.InvalidRankingJoinException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.JoinOpenRankingUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rankings/{rankingId}/join")
public class JoinOpenRankingController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final JoinOpenRankingUseCase rankings;

  public JoinOpenRankingController(JoinOpenRankingUseCase rankings) { this.rankings = rankings; }

  @PostMapping
  public ResponseEntity<Void> join(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingAccessDeniedException();
    requireEmpty(request);
    if (rankingId == null || !UUID_PATTERN.matcher(rankingId).matches()) throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    rankings.join(actor, UUID.fromString(rankingId));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private void requireEmpty(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > 0) throw new InvalidRankingJoinException();
    try {
      // Covers chunked/unknown lengths without deserializing or retaining any request payload.
      if (request.getInputStream().read() != -1) throw new InvalidRankingJoinException();
    } catch (IOException unreadable) {
      throw new InvalidRankingJoinException();
    }
  }

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return error(HttpStatus.NOT_FOUND, "ranking.not_found");
  }

  @ExceptionHandler(InvalidRankingJoinException.class)
  public ResponseEntity<Map<String, String>> invalid() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_join");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
