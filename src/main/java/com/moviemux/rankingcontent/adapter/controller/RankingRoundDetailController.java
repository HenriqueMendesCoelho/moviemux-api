package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.ReadRankingRoundUseCase;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/entries/{entryId}/rounds/{roundId}", "/rankings/{rankingId}/entries/{entryId}/rounds/{roundId}"})
public class RankingRoundDetailController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final ReadRankingRoundUseCase rounds;

  public RankingRoundDetailController(ReadRankingRoundUseCase rounds) { this.rounds = rounds; }

  @GetMapping
  public ResponseEntity<RoundResponse> get(@PathVariable String rankingId, @PathVariable String entryId,
      @PathVariable String roundId, @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var round = rounds.read(actor, scopedId(rankingId), scopedId(entryId), scopedId(roundId));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new RoundResponse(round.roundId(), round.roundNumber(), round.revealed()));
  }

  // Spring otherwise dispatches HEAD to GET, which could persist a reveal.
  @RequestMapping(method = RequestMethod.HEAD)
  public ResponseEntity<Void> head() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).cacheControl(CacheControl.noStore())
        .header(HttpHeaders.ALLOW, "GET").build();
  }

  private UUID scopedId(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingEntriesDeniedException();
    return UUID.fromString(value);
  }

  public record RoundResponse(UUID roundId, long roundNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) Boolean revealed) {}

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
