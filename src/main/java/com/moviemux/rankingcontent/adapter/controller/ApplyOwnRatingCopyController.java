package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.usecase.ApplyOwnRatingCopyUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({
    "/movies/collections/{rankingId}/entries/{entryId}/rounds/{roundId}/rating/me/copy-apply",
    "/rankings/{rankingId}/entries/{entryId}/rounds/{roundId}/rating/me/copy-apply"
})
public class ApplyOwnRatingCopyController {
  // Allows escaped JSON representation of every 264-character token, but never buffers arbitrary input.
  private static final int MAX_BODY_BYTES = 2048;
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final ApplyOwnRatingCopyUseCase copies;

  public ApplyOwnRatingCopyController(ApplyOwnRatingCopyUseCase copies) { this.copies = copies; }

  @PostMapping
  public ResponseEntity<RatingResponse> apply(@PathVariable String rankingId, @PathVariable String entryId,
      @PathVariable String roundId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var target = new RatingCopyContext(uuid(rankingId), uuid(entryId), uuid(roundId));
    var result = copies.apply(actor, target, token(request));
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
        .body(new RatingResponse(result.roundId(), result.score()));
  }

  private String token(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new InvalidRatingCopyException();
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw new InvalidRatingCopyException();
      JsonNode body = BODY.readValue(bytes);
      if (body == null || !body.isObject() || body.size() != 1 || !body.path("preview_token").isTextual())
        throw new InvalidRatingCopyException();
      return body.get("preview_token").textValue();
    } catch (IOException malformed) {
      throw new InvalidRatingCopyException();
    }
  }

  private UUID uuid(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new InvalidRatingCopyException();
    return UUID.fromString(value);
  }

  public record RatingResponse(UUID roundId, int score) {
    @Override public String toString() { return "RatingResponse[redacted]"; }
  }

  @ExceptionHandler(InvalidRatingCopyException.class)
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_rating_copy"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(StaleRatingCopyException.class)
  public ResponseEntity<Map<String, String>> stale() { return error(HttpStatus.CONFLICT, "ranking.rating_copy_stale"); }

  @ExceptionHandler(DuplicateOwnRoundRatingException.class)
  public ResponseEntity<Map<String, String>> duplicate() { return error(HttpStatus.CONFLICT, "ranking.rating_exists"); }

  @ExceptionHandler(RatingCopyUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.rating_copy_unavailable");
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
