package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.ArchivedCatalogEntryException;
import com.moviemux.rankingcontent.domain.exception.InvalidTmdbSearchException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.exception.TmdbImportUnavailableException;
import com.moviemux.rankingcontent.domain.exception.TmdbProviderFailureException;
import com.moviemux.rankingcontent.domain.usecase.SearchTmdbPickerUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rankings/{rankingId}/entries/tmdb/search")
public class SearchTmdbPickerController {
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final SearchTmdbPickerUseCase picker;

  public SearchTmdbPickerController(SearchTmdbPickerUseCase picker) { this.picker = picker; }

  @PostMapping
  public ResponseEntity<SearchResponse> search(@PathVariable String rankingId, @RequestBody byte[] rawBody,
      HttpServletRequest request, @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    if (request.getQueryString() != null || rankingId == null || !UUID_PATTERN.matcher(rankingId).matches())
      throw new InvalidTmdbSearchException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var body = parse(rawBody);
    var result = picker.search(actor, UUID.fromString(rankingId), body.query(), body.page(), body.language());
    var items = result.items().stream().map(item -> new ItemResponse(
        item.tmdbId(), item.title(), item.originalTitle(), item.releaseDate(), item.posterPath())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SearchResponse(items, result.page(), result.hasMore()));
  }

  private SearchRequest parse(byte[] rawBody) {
    if (rawBody == null) throw new InvalidTmdbSearchException();
    try {
      JsonNode body = BODY.readValue(rawBody);
      if (body == null || !body.isObject() || body.size() != 3 || !body.path("query").isTextual()
          || !body.path("page").isIntegralNumber() || !body.path("page").canConvertToInt()
          || !body.path("language").isTextual()) throw new InvalidTmdbSearchException();
      return new SearchRequest(body.get("query").textValue(), body.get("page").intValue(), body.get("language").textValue());
    } catch (IOException invalid) {
      throw new InvalidTmdbSearchException();
    }
  }

  private record SearchRequest(String query, int page, String language) {}

  public record SearchResponse(List<ItemResponse> items, int page, boolean hasMore) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ItemResponse(long tmdbId, String title, String originalTitle, LocalDate releaseDate, String posterPath) {}

  @ExceptionHandler({InvalidTmdbSearchException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_tmdb_search"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(ArchivedCatalogEntryException.class)
  public ResponseEntity<Map<String, String>> archived() { return error(HttpStatus.CONFLICT, "ranking.archived_read_only"); }

  @ExceptionHandler(TmdbImportUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.tmdb_import_unavailable"); }

  @ExceptionHandler(TmdbProviderFailureException.class)
  public ResponseEntity<Map<String, String>> providerFailed(TmdbProviderFailureException failure) {
    return switch (failure.kind()) {
      case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "ranking.tmdb_invalid_response");
      case REMOTE_STATUS, TRANSPORT -> error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.tmdb_unavailable");
    };
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
