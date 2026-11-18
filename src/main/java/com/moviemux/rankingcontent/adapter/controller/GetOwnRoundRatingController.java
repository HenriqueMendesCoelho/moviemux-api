package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.GetOwnRoundRatingUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/rankings/{rankingId}/rounds/{roundId}/rating/me")
public class GetOwnRoundRatingController {
  private final GetOwnRoundRatingUseCase useCase;
  public GetOwnRoundRatingController(GetOwnRoundRatingUseCase useCase){this.useCase=useCase;}
  @GetMapping
  public ResponseEntity<RatingResponse> get(@PathVariable UUID rankingId, @PathVariable UUID roundId,
      @AuthenticationPrincipal(expression="userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if(actorId==null || principal==null)throw new RankingEntriesDeniedException();
    var actor=new RankingActor(actorId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result=useCase.get(actor,rankingId,roundId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new RatingResponse(result.roundId(),result.score()));
  }
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record RatingResponse(UUID roundId, Integer score) {}
  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound(){return error(HttpStatus.NOT_FOUND,"ranking.not_found");}
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"ranking.invalid_rating");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> failed(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(HttpStatus status,String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
  }
}
