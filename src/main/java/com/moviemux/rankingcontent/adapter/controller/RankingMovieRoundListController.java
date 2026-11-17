package com.moviemux.rankingcontent.adapter.controller;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.usecase.RankingMovieRoundListUseCase;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestController
@RequestMapping("/rankings/{rankingId}/entries/{entryId}/rounds")
public class RankingMovieRoundListController {
  private final RankingMovieRoundListUseCase useCase;
  public RankingMovieRoundListController(RankingMovieRoundListUseCase useCase) { this.useCase=useCase; }
  @GetMapping
  public ResponseEntity<PageResponse> list(@PathVariable UUID rankingId, @PathVariable UUID entryId,
      @RequestParam(required=false) Long afterNumber, @RequestParam(defaultValue="20") int limit,
      @AuthenticationPrincipal(expression="userid") UUID actorUserId, @AuthenticationPrincipal UserDetails principal) {
    if (principal == null || actorUserId == null) throw new RankingAccessDeniedException();
    var actor = new RankingActor(actorUserId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var page=useCase.execute(actor,rankingId,entryId,afterNumber,limit);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(page.items().stream()
        .map(r -> new RoundResponse(r.roundId(),r.number(),r.description(),r.createdAt(),r.updatedAt(),r.revealed())).toList(),page.nextCursor()));
  }
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record RoundResponse(UUID roundId,long number,String description,Instant createdAt,Instant updatedAt,Boolean revealed) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PageResponse(List<RoundResponse> items,Long nextCursor) {}
  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).body(Map.of("code","ranking.not_found"));
  }
  @ExceptionHandler({InvalidRankingAccessPageException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> invalidPage() {
    return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("code","ranking.invalid_page"));
  }
  @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
  public ResponseEntity<Map<String,String>> operationFailed() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore())
        .body(Map.of("code","ranking.operation_failed"));
  }

}
