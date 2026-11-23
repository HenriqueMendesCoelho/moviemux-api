package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult.Status;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.usecase.PreviewOwnRatingCopyUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/entries/{entryId}/rounds/{roundId}/rating/me/copy-preview", "/rankings/{rankingId}/entries/{entryId}/rounds/{roundId}/rating/me/copy-preview"})
public class PreviewOwnRatingCopyController {
  private static final int MAX_BODY_BYTES = 1024;
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final PreviewOwnRatingCopyUseCase copies;

  public PreviewOwnRatingCopyController(PreviewOwnRatingCopyUseCase copies) { this.copies = copies; }

  @PostMapping
  public ResponseEntity<PreviewResponse> preview(@PathVariable String rankingId, @PathVariable String entryId,
      @PathVariable String roundId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var destination = new RatingCopyContext(uuid(rankingId), uuid(entryId), uuid(roundId));
    var result = copies.preview(actor, new RatingCopyPair(source(request), destination));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new PreviewResponse(result.status(), result.sourceScore(), result.previewToken(), result.expiresAt()));
  }

  private RatingCopyContext source(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new InvalidRatingCopyException();
    try {
      byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (body.length == 0 || body.length > MAX_BODY_BYTES) throw new InvalidRatingCopyException();
      JsonNode parsed = BODY.readValue(body);
      if (parsed == null || !parsed.isObject() || parsed.size() != 1) throw new InvalidRatingCopyException();
      JsonNode source = parsed.path("source");
      if (!source.isObject() || source.size() != 3) throw new InvalidRatingCopyException();
      return new RatingCopyContext(uuid(text(source, "ranking_id")), uuid(text(source, "entry_id")),
          uuid(text(source, "round_id")));
    } catch (IOException malformed) {
      throw new InvalidRatingCopyException();
    }
  }

  private String text(JsonNode object, String key) {
    var value = object.path(key);
    if (!value.isTextual()) throw new InvalidRatingCopyException();
    return value.textValue();
  }

  private UUID uuid(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new InvalidRatingCopyException();
    return UUID.fromString(value);
  }

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PreviewResponse(Status status, Integer sourceScore, String previewToken, Instant expiresAt) {
    @Override public String toString() { return "PreviewResponse[status=" + status + "]"; }
  }

  @ExceptionHandler(InvalidRatingCopyException.class)
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_rating_copy"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(RatingCopyUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.rating_copy_unavailable");
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
