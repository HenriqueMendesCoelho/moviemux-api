package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.ManageRepeatedRoundsUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections/{rankingId}", "/rankings/{rankingId}"})
public class RepeatedRoundController {
  private static final int MAX_BODY_BYTES = 4096;
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final ManageRepeatedRoundsUseCase rounds;
  public RepeatedRoundController(ManageRepeatedRoundsUseCase rounds) { this.rounds = rounds; }

  @GetMapping("/settings/rounds")
  public ResponseEntity<SettingsResponse> settings(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    UUID ranking = scopedId(rankingId);
    if (request.getQueryString() != null || request.getContentLengthLong() > 0) throw new InvalidRoundSettingsException();
    try {
      if (request.getInputStream().read() != -1) throw new InvalidRoundSettingsException();
    } catch (IOException invalid) {
      throw new InvalidRoundSettingsException();
    }
    return settingsResponse(rounds.settings(actor, ranking));
  }

  @PutMapping("/settings/rounds")
  public ResponseEntity<SettingsResponse> setSettings(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    UUID ranking = scopedId(rankingId);
    JsonNode body = read(request, InvalidRoundSettingsException::new);
    if (body.size() != 1 || !body.path("repeat_enabled").isBoolean()) throw new InvalidRoundSettingsException();
    return settingsResponse(rounds.setSettings(actor, ranking, body.get("repeat_enabled").booleanValue()));
  }

  @PostMapping("/entries/{entryId}/rounds")
  public ResponseEntity<RoundResponse> create(@PathVariable String rankingId, @PathVariable String entryId,
      HttpServletRequest request, @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    UUID ranking = scopedId(rankingId), entry = scopedId(entryId);
    JsonNode body = read(request, InvalidRoundCommandException::new);
    var command = body.path("command_id");
    var description = body.get("description");
    if (body.size() != (description == null ? 1 : 2) || !command.isTextual()
        || !UUID_PATTERN.matcher(command.textValue()).matches()
        || (description != null && !description.isNull() && !description.isTextual()))
      throw new InvalidRoundCommandException();
    var receipt = rounds.create(actor, ranking, entry, UUID.fromString(command.textValue()),
        description == null ? null : description.textValue());
    return ResponseEntity.status(receipt.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
        .body(new RoundResponse(receipt.roundId(), receipt.roundNumber()));
  }

  private ResponseEntity<SettingsResponse> settingsResponse(boolean enabled) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SettingsResponse(enabled));
  }

  private JsonNode read(HttpServletRequest request, Supplier<? extends RuntimeException> invalid) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES) throw invalid.get();
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw invalid.get();
      JsonNode body = BODY.readValue(bytes);
      if (body == null || !body.isObject()) throw invalid.get();
      return body;
    } catch (IOException malformed) {
      throw invalid.get();
    }
  }

  private RankingActor actor(UUID actorId, UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingEntriesDeniedException();
    return new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  private UUID scopedId(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingEntriesDeniedException();
    return UUID.fromString(value);
  }

  public record SettingsResponse(boolean repeatEnabled) {}
  public record RoundResponse(UUID roundId, long roundNumber) {}

  @ExceptionHandler(InvalidRoundSettingsException.class)
  public ResponseEntity<Map<String, String>> invalidSettings() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_round_settings");
  }

  @ExceptionHandler(InvalidRoundCommandException.class)
  public ResponseEntity<Map<String, String>> invalidCommand() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_round_command");
  }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  @ExceptionHandler(RepeatRoundsDisabledException.class)
  public ResponseEntity<Map<String, String>> disabled() {
    return error(HttpStatus.CONFLICT, "ranking.repeat_rounds_disabled");
  }

  @ExceptionHandler(RoundCommandConflictException.class)
  public ResponseEntity<Map<String, String>> conflict() {
    return error(HttpStatus.CONFLICT, "ranking.round_command_conflict");
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<Map<String, String>> failed() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "ranking.operation_failed");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
