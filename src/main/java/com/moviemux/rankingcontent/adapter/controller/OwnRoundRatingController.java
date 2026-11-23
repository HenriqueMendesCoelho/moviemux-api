package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.WriteOwnRoundRatingUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/rounds/{roundId}/rating/me", "/rankings/{rankingId}/rounds/{roundId}/rating/me"})
public class OwnRoundRatingController {
  private final WriteOwnRoundRatingUseCase useCase;
  public OwnRoundRatingController(WriteOwnRoundRatingUseCase useCase){this.useCase=useCase;}
  @PostMapping
  public ResponseEntity<RatingResponse> create(@PathVariable UUID rankingId,@PathVariable UUID roundId,
      @RequestBody RatingRequest body,@AuthenticationPrincipal(expression="userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor=actor(actorId,principal);int score=score(body);
    var result=useCase.create(actor,rankingId,roundId,score);
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(new RatingResponse(result.roundId(),result.score()));
  }
  @PatchMapping
  public ResponseEntity<RatingResponse> update(@PathVariable UUID rankingId,@PathVariable UUID roundId,
      @RequestBody RatingRequest body,@AuthenticationPrincipal(expression="userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor=actor(actorId,principal);int score=score(body);
    var result=useCase.update(actor,rankingId,roundId,score);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RatingResponse(result.roundId(),result.score()));
  }
  private RankingActor actor(UUID actorId,UserDetails principal) {
    if(actorId==null || principal==null)throw new RankingEntriesDeniedException();
    return new RankingActor(actorId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }
  private int score(RatingRequest body) {
    if(body==null || body.score()==null || !body.score().isIntegralNumber() || !body.score().canConvertToInt())throw new InvalidOwnRoundRatingException();
    int value=body.score().intValue();
    if(value<0 || value>10)throw new InvalidOwnRoundRatingException();
    return value;
  }
  public record RatingRequest(JsonNode score) {}
  public record RatingResponse(UUID roundId,int score) {}
  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound(){return error(HttpStatus.NOT_FOUND,"ranking.not_found");}
  @ExceptionHandler(DuplicateOwnRoundRatingException.class)
  public ResponseEntity<Map<String,String>> duplicate(){return error(HttpStatus.CONFLICT,"ranking.rating_exists");}
  @ExceptionHandler(ArchivedOwnRoundRatingException.class)
  public ResponseEntity<Map<String,String>> archived(){return error(HttpStatus.CONFLICT,"ranking.archived_read_only");}
  @ExceptionHandler({InvalidOwnRoundRatingException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"ranking.invalid_rating");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> failed(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
