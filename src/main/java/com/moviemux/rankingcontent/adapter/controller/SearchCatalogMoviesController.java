package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.ArchivedCatalogEntryException;
import com.moviemux.rankingcontent.domain.exception.InvalidCatalogMovieSearchException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.usecase.SearchCatalogMoviesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/entries/catalog/search", "/rankings/{rankingId}/entries/catalog/search"})
public class SearchCatalogMoviesController {
  private static final int MAX_BODY_BYTES = 8192;
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final SearchCatalogMoviesUseCase catalog;

  public SearchCatalogMoviesController(SearchCatalogMoviesUseCase catalog) { this.catalog = catalog; }

  @PostMapping
  public ResponseEntity<SearchResponse> search(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    if (request.getQueryString() != null || rankingId == null || !UUID_PATTERN.matcher(rankingId).matches())
      throw new InvalidCatalogMovieSearchException();
    var body = read(request);
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result = catalog.search(actor, UUID.fromString(rankingId), body.get("query").textValue(),
        body.get("page").intValue(), body.get("language").textValue());
    var items = result.items().stream().map(item -> new ItemResponse(item.movieId(), item.title(), item.releaseDate())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SearchResponse(items, result.page(), result.hasMore()));
  }

  private static JsonNode read(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new InvalidCatalogMovieSearchException();
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw new InvalidCatalogMovieSearchException();
      JsonNode body = BODY.readValue(bytes);
      if (body == null || !body.isObject() || body.size() != 3 || !body.path("query").isTextual()
          || !body.path("page").isIntegralNumber() || !body.path("page").canConvertToInt()
          || !body.path("language").isTextual()) throw new InvalidCatalogMovieSearchException();
      return body;
    } catch (IOException invalid) {
      throw new InvalidCatalogMovieSearchException();
    }
  }

  public record SearchResponse(List<ItemResponse> items, int page, boolean hasMore) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ItemResponse(UUID movieId, String title, LocalDate releaseDate) {}

  @ExceptionHandler(InvalidCatalogMovieSearchException.class)
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_catalog_search"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(ArchivedCatalogEntryException.class)
  public ResponseEntity<Map<String, String>> archived() { return error(HttpStatus.CONFLICT, "ranking.archived_read_only"); }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> failed() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
