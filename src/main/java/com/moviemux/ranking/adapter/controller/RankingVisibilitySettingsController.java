package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.exception.InvalidVisibilitySettingsException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.usecase.ManageRankingVisibilityUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rankings/{rankingId}/settings/visibility")
public class RankingVisibilitySettingsController {
  private static final int MAX_BODY_BYTES = 1024;
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private final ManageRankingVisibilityUseCase settings;

  public RankingVisibilitySettingsController(ManageRankingVisibilityUseCase settings) { this.settings = settings; }

  @GetMapping
  public ResponseEntity<VisibilityResponse> current(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    var rank = scope(rankingId);
    if (request.getQueryString() != null || request.getContentLengthLong() > 0)
      throw new InvalidVisibilitySettingsException();
    try {
      if (request.getInputStream().read() != -1) throw new InvalidVisibilitySettingsException();
    } catch (IOException invalid) {
      throw new InvalidVisibilitySettingsException();
    }
    return response(settings.current(actor, rank));
  }

  @PutMapping
  public ResponseEntity<VisibilityResponse> set(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    var rank = scope(rankingId);
    return response(settings.set(actor, rank, parse(request)));
  }

  private RankingVisibility parse(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new InvalidVisibilitySettingsException();
    try {
      byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw new InvalidVisibilitySettingsException();
      JsonNode body = BODY.readValue(bytes);
      if (body == null || !body.isObject() || body.size() != 1 || !body.path("visibility").isTextual())
        throw new InvalidVisibilitySettingsException();
      return switch (body.get("visibility").textValue()) {
        case "PUBLIC" -> RankingVisibility.PUBLIC;
        case "PRIVATE" -> RankingVisibility.PRIVATE;
        default -> throw new InvalidVisibilitySettingsException();
      };
    } catch (IOException invalid) {
      throw new InvalidVisibilitySettingsException();
    }
  }

  private RankingActor actor(UUID actorId, UserDetails principal) {
    if (actorId == null || principal == null) throw new RankingAccessDeniedException();
    return new RankingActor(actorId, principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }

  private UUID scope(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingAccessDeniedException();
    return UUID.fromString(value);
  }

  private ResponseEntity<VisibilityResponse> response(RankingVisibility visibility) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VisibilityResponse(visibility));
  }

  public record VisibilityResponse(RankingVisibility visibility) {}

  @ExceptionHandler(InvalidVisibilitySettingsException.class)
  public ResponseEntity<Map<String, String>> invalid() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_visibility_settings");
  }

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "ranking.not_found"); }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
