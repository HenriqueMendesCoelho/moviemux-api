package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.rankingcontent.domain.exception.InvalidPublicRankingPageException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.ReadPublicRankingUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/public/movies/collections/{rankingId}", "/public/rankings/{rankingId}"})
public class PublicRankingProjectionController {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,10}");
  private static final int MAX_QUERY_CHARS = 64;
  private final ReadPublicRankingUseCase rankings;

  public PublicRankingProjectionController(ReadPublicRankingUseCase rankings) { this.rankings = rankings; }

  @GetMapping
  public ResponseEntity<SummaryResponse> summary(@PathVariable String rankingId, HttpServletRequest request) {
    UUID rank = scope(rankingId);
    var result = rankings.summary(rank);
    if (request.getQueryString() != null || hasBody(request)) throw new InvalidPublicRankingPageException();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(new SummaryResponse(result.rankingId(), result.rankingName()));
  }

  @GetMapping("/entries")
  public ResponseEntity<EntriesResponse> entries(@PathVariable String rankingId, HttpServletRequest request) {
    UUID rank = scope(rankingId);
    final Options options;
    try {
      if (hasBody(request)) throw new InvalidPublicRankingPageException();
      options = options(request.getQueryString());
    } catch (InvalidPublicRankingPageException invalid) {
      // Malformed options never confirm that a hidden ranking exists.
      rankings.summary(rank);
      throw invalid;
    }
    var result = rankings.entries(rank, options.after(), options.limit());
    var items = result.items().stream()
        .map(item -> new ItemResponse(item.entryId(), item.title(), item.total(), item.votes())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new EntriesResponse(items, result.nextCursor()));
  }

  private Options options(String query) {
    if (query == null) return new Options(null, ReadPublicRankingUseCase.DEFAULT_LIMIT);
    if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) throw new InvalidPublicRankingPageException();
    UUID after = null;
    Integer limit = null;
    // The only accepted values are canonical UUID or digits, which require no URL encoding.
    for (String part : query.split("&", -1)) {
      if (part.startsWith("after=")) {
        var value = part.substring(6);
        if (after != null || !UUID_PATTERN.matcher(value).matches()) throw new InvalidPublicRankingPageException();
        after = UUID.fromString(value);
      } else if (part.startsWith("limit=")) {
        var value = part.substring(6);
        if (limit != null || !DECIMAL.matcher(value).matches()) throw new InvalidPublicRankingPageException();
        try { limit = Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new InvalidPublicRankingPageException(); }
      } else throw new InvalidPublicRankingPageException();
    }
    return new Options(after, limit == null ? ReadPublicRankingUseCase.DEFAULT_LIMIT : limit);
  }

  private boolean hasBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0) return true;
    try { return request.getInputStream().read() != -1; }
    catch (IOException unreadable) { return true; }
  }

  private UUID scope(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingEntriesDeniedException();
    return UUID.fromString(value);
  }

  private record Options(UUID after, int limit) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SummaryResponse(UUID rankingId, String rankingName) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ItemResponse(UUID entryId, String title, BigInteger total, BigInteger votes) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record EntriesResponse(List<ItemResponse> items, UUID nextCursor) {}

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(InvalidPublicRankingPageException.class)
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_page"); }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> unavailable() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
