package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.ImportTmdbCatalogEntryUseCase;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rankings")
public class ImportTmdbCatalogEntryController {
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private final ImportTmdbCatalogEntryUseCase useCase;

  public ImportTmdbCatalogEntryController(ImportTmdbCatalogEntryUseCase useCase) { this.useCase = useCase; }

  @PostMapping("/{rankingId}/entries/tmdb")
  public ResponseEntity<EntryResponse> add(@PathVariable String rankingId, @RequestBody byte[] body,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    if (actorId == null || principal == null || rankingId == null || !UUID_PATTERN.matcher(rankingId).matches())
      throw new RankingEntriesDeniedException();
    var actor = new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result = useCase.execute(actor, UUID.fromString(rankingId), tmdbId(body));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
        .body(new EntryResponse(result.entryId()));
  }

  private long tmdbId(byte[] rawBody) {
    if (rawBody == null) throw new InvalidTmdbIdException();
    try {
      JsonNode body = BODY.readValue(rawBody);
      if (body == null || !body.isObject() || body.size() != 1) throw new InvalidTmdbIdException();
      JsonNode id = body.path("tmdb_id");
      if (!id.isIntegralNumber() || !id.canConvertToLong()) throw new InvalidTmdbIdException();
      long value = id.longValue();
      if (value < 1 || value > ImportTmdbCatalogEntryUseCase.MAX_TMDB_ID) throw new InvalidTmdbIdException();
      return value;
    } catch (IOException invalid) {
      throw new InvalidTmdbIdException();
    }
  }

  public record EntryResponse(UUID entryId) {}

  @ExceptionHandler({InvalidTmdbIdException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "ranking.invalid_tmdb_id"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(ArchivedCatalogEntryException.class)
  public ResponseEntity<Map<String, String>> archived() { return error(HttpStatus.CONFLICT, "ranking.archived_read_only"); }

  @ExceptionHandler(ExpiredEntryRecoveryException.class)
  public ResponseEntity<Map<String, String>> expired() { return error(HttpStatus.CONFLICT, "ranking.entry_recovery_expired"); }

  @ExceptionHandler(CatalogIdentityConflictException.class)
  public ResponseEntity<Map<String, String>> identityConflict() { return error(HttpStatus.CONFLICT, "ranking.catalog_identity_conflict"); }

  @ExceptionHandler(TmdbImportUnavailableException.class)
  public ResponseEntity<Map<String, String>> featureUnavailable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.tmdb_import_unavailable");
  }

  @ExceptionHandler(TmdbProviderFailureException.class)
  public ResponseEntity<Map<String, String>> providerFailed(TmdbProviderFailureException failure) {
    return switch (failure.kind()) {
      case INVALID_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "ranking.tmdb_invalid_response");
      case REMOTE_STATUS -> failure.status() == 404
          ? error(HttpStatus.NOT_FOUND, "ranking.tmdb_not_found")
          : error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.tmdb_unavailable");
      case TRANSPORT -> error(HttpStatus.SERVICE_UNAVAILABLE, "ranking.tmdb_unavailable");
    };
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
