package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.ResolveLegacyFilmLinkUseCase;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/movies/collections/legacy-film-links/{legacyMovieId}", "/rankings/legacy-film-links/{legacyMovieId}"})
public class ResolveLegacyFilmLinkController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private final ResolveLegacyFilmLinkUseCase links;

  public ResolveLegacyFilmLinkController(ResolveLegacyFilmLinkUseCase links) { this.links = links; }

  @GetMapping
  public ResponseEntity<LinkResponse> get(@PathVariable String legacyMovieId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null || legacyMovieId == null || !UUID_PATTERN.matcher(legacyMovieId).matches()
        || !"GET".equals(request.getMethod()) || request.getQueryString() != null || request.getContentLengthLong() > 0)
      throw new RankingEntriesDeniedException();
    try {
      if (request.getInputStream().read() != -1) throw new RankingEntriesDeniedException();
    } catch (IOException unreadable) {
      throw new RankingEntriesDeniedException();
    }

    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var target = links.resolve(actor, UUID.fromString(legacyMovieId));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LinkResponse(target.rankingId(), target.entryId()));
  }

  public record LinkResponse(@JsonProperty("ranking_id") UUID rankingId, @JsonProperty("entry_id") UUID entryId) {}

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.not_found"));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> failed() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore())
        .body(Map.of("code", "ranking.operation_failed"));
  }
}
