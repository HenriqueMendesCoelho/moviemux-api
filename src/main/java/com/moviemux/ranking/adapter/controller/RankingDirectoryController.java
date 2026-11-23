package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.usecase.CreateRankingUseCase;
import com.moviemux.ranking.domain.usecase.ListRankingSummariesUseCase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping({"/movies/collections", "/rankings"})
public class RankingDirectoryController {
  private final CreateRankingUseCase creator;
  private final ListRankingSummariesUseCase directory;

  public RankingDirectoryController(CreateRankingUseCase creator, ListRankingSummariesUseCase directory) {
    this.creator = creator;
    this.directory = directory;
  }

  @PostMapping
  public ResponseEntity<SummaryResponse> create(@RequestBody JsonNode body,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    if (body == null || !body.isObject() || body.size() != 3
        || !body.path("name").isTextual() || !body.path("visibility").isTextual()
        || !body.path("admission").isTextual()) throw new InvalidRankingCreationException();
    final RankingVisibility visibility;
    final RankingAdmission admission;
    try {
      visibility = RankingVisibility.valueOf(body.get("visibility").textValue());
      admission = RankingAdmission.valueOf(body.get("admission").textValue());
    } catch (IllegalArgumentException invalid) {
      throw new InvalidRankingCreationException();
    }
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
        .body(SummaryResponse.from(creator.create(actor, body.get("name").textValue(), visibility, admission)));
  }

  @GetMapping
  public ResponseEntity<PageResponse> list(@RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "20") int limit,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var page = directory.list(actor(actorId, principal), after, limit);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new PageResponse(page.items().stream().map(SummaryResponse::from).toList(), page.nextCursor()));
  }

  private static RankingActor actor(UUID id, UserDetails principal) {
    if (id == null || principal == null) throw new RankingAccessDeniedException();
    return new RankingActor(id, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  public record SummaryResponse(UUID rankingId, @JsonInclude(JsonInclude.Include.ALWAYS) String name,
      RankingVisibility visibility, RankingAdmission admission, Set<RankingCapability> capabilities) {
    static SummaryResponse from(RankingSummary item) {
      return new SummaryResponse(item.rankingId(), item.name(), item.visibility(), item.admission(), item.capabilities());
    }
  }

  public record PageResponse(List<SummaryResponse> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID nextCursor) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler({InvalidRankingAccessPageException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> badPage() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_page"); }

  @ExceptionHandler({InvalidRankingCreationException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, String>> badCreation() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_creation"); }

  @ExceptionHandler(RankingCreationUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.creation_unavailable");
  }

  @ExceptionHandler(RankingCreationLimitException.class)
  public ResponseEntity<Map<String, String>> limitReached() { return error(HttpStatus.CONFLICT, "ranking.creation_limit_reached"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
