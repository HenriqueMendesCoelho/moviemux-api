package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.GetEntryRatingAggregateUseCase;
import java.util.Map;
import java.math.BigInteger;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/entries/{entryId}/ratings/aggregate", "/rankings/{rankingId}/entries/{entryId}/ratings/aggregate"})
public class GetEntryRatingAggregateController {
  private final GetEntryRatingAggregateUseCase useCase;
  public GetEntryRatingAggregateController(GetEntryRatingAggregateUseCase useCase){this.useCase=useCase;}
  @GetMapping
  public ResponseEntity<AggregateResponse> get(@PathVariable UUID rankingId, @PathVariable UUID entryId,
      @AuthenticationPrincipal(expression="userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if(actorId==null || principal==null)throw new RankingEntriesDeniedException();
    var actor=new RankingActor(actorId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result=useCase.get(actor,rankingId,entryId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new AggregateResponse(result.entryId(),result.total(),result.votes(),result.partial()));
  }
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record AggregateResponse(UUID entryId, BigInteger total, BigInteger votes, boolean partial) {}
  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound(){return error(HttpStatus.NOT_FOUND,"ranking.not_found");}
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"ranking.invalid_rating_aggregate");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> failed(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(HttpStatus status,String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
  }
}
