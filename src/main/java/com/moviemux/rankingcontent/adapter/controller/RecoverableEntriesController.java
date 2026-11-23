package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.ManageRecoverableEntriesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
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
@RequestMapping({"/movies/collections/{rankingId}/entries", "/rankings/{rankingId}/entries"})
public class RecoverableEntriesController {
  private static final int MAX_QUERY_CHARS = 64;
  private static final int MAX_BODY_BYTES = 512;
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern LIMIT = Pattern.compile("[0-9]{1,10}");
  private static final Pattern VERSION = Pattern.compile("0|[1-9][0-9]{0,18}");
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ManageRecoverableEntriesUseCase entries;

  public RecoverableEntriesController(ManageRecoverableEntriesUseCase entries) { this.entries = entries; }

  @GetMapping("/recovery")
  public ResponseEntity<PageResponse> list(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    UUID ranking = scope(rankingId);
    requireNoBody(request);
    var page = options(request.getQueryString());
    var result = entries.list(actor, ranking, page.after(), page.limit());
    var items = result.items().stream().map(item -> new ItemResponse(item.entryId(), item.title(),
        item.removedAt().toInstant().toString(), item.expiresAt().toInstant().toString(), Long.toString(item.version()))).toList();
    return response(new PageResponse(items, result.nextCursor()));
  }

  @PostMapping("/{entryId}/restore-current")
  public ResponseEntity<RestoredResponse> restoreCurrent(@PathVariable String rankingId, @PathVariable String entryId,
      HttpServletRequest request, @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    UUID ranking = scope(rankingId), entry = scope(entryId);
    var result = entries.restoreCurrent(actor, ranking, entry, expectedVersion(request));
    return response(new RestoredResponse(result.entryId(), Long.toString(result.version())));
  }

  private void requireNoBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0) throw new InvalidEntryRecoveryException();
    try {
      if (request.getInputStream().read() != -1) throw new InvalidEntryRecoveryException();
    } catch (IOException unreadable) {
      throw new InvalidEntryRecoveryException();
    }
  }

  private Options options(String query) {
    if (query == null) return new Options(null, ManageRecoverableEntriesUseCase.DEFAULT_LIMIT);
    if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) throw new InvalidEntryRecoveryException();
    UUID after = null;
    Integer limit = null;
    for (String part : query.split("&", -1)) {
      if (part.startsWith("after=")) {
        String value = part.substring(6);
        if (after != null || !UUID_PATTERN.matcher(value).matches()) throw new InvalidEntryRecoveryException();
        after = UUID.fromString(value);
      } else if (part.startsWith("limit=")) {
        String value = part.substring(6);
        if (limit != null || !LIMIT.matcher(value).matches()) throw new InvalidEntryRecoveryException();
        try { limit = Integer.parseInt(value); }
        catch (NumberFormatException malformed) { throw new InvalidEntryRecoveryException(); }
      } else throw new InvalidEntryRecoveryException();
    }
    return new Options(after, limit == null ? ManageRecoverableEntriesUseCase.DEFAULT_LIMIT : limit);
  }

  private long expectedVersion(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new InvalidEntryRecoveryException();
    try {
      byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (raw.length == 0 || raw.length > MAX_BODY_BYTES) throw new InvalidEntryRecoveryException();
      JsonNode body = BODY.readValue(raw);
      if (body == null || !body.isObject() || body.size() != 1 || !body.path("expected_version").isTextual())
        throw new InvalidEntryRecoveryException();
      String version = body.get("expected_version").textValue();
      if (!VERSION.matcher(version).matches()) throw new InvalidEntryRecoveryException();
      long parsed = Long.parseLong(version);
      if (parsed == Long.MAX_VALUE) throw new InvalidEntryRecoveryException();
      return parsed;
    } catch (IOException | NumberFormatException malformed) {
      throw new InvalidEntryRecoveryException();
    }
  }

  private RankingActor actor(UUID actorId, UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    return new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  private UUID scope(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingEntriesDeniedException();
    return UUID.fromString(value);
  }

  private <T> ResponseEntity<T> response(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  private record Options(UUID after, int limit) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ItemResponse(UUID entryId, String title, String removedAt, String expiresAt, String version) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PageResponse(List<ItemResponse> items, UUID nextCursor) {}
  public record RestoredResponse(UUID entryId, String version) {}

  @ExceptionHandler(InvalidEntryRecoveryException.class)
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_recovery_request"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> denied() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(StaleEntryRecoveryException.class)
  public ResponseEntity<Map<String, String>> stale() { return error(HttpStatus.CONFLICT, "ranking.entry_state_stale"); }

  @ExceptionHandler(ExpiredEntryRecoveryException.class)
  public ResponseEntity<Map<String, String>> expired() { return error(HttpStatus.CONFLICT, "ranking.entry_recovery_expired"); }

  @ExceptionHandler(ArchivedEntryRecoveryException.class)
  public ResponseEntity<Map<String, String>> archived() { return error(HttpStatus.CONFLICT, "ranking.archived_read_only"); }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
