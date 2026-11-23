package com.moviemux.ranking.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.InvalidRankingCapabilitiesException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.usecase.ManageMembershipRosterUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/movies/collections/{rankingId}/members", "/rankings/{rankingId}/members"})
public class RankingMembershipRosterController {
  private static final int MAX_QUERY_CHARS = 64;
  private static final int MAX_BODY_BYTES = 16_384;
  private static final int MAX_GRANT_VALUES = 100;
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern LIMIT = Pattern.compile("[0-9]{1,10}");
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ManageMembershipRosterUseCase roster;

  public RankingMembershipRosterController(ManageMembershipRosterUseCase roster) { this.roster = roster; }

  @GetMapping
  public ResponseEntity<PageResponse> members(@PathVariable String rankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression = "userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    var rank = scope(rankingId);
    var options = page(request);
    var result = roster.list(actor, rank, options.after(), options.limit());
    var items = result.items().stream()
        .map(member -> new MemberResponse(member.membershipId(), member.displayName(),
            member.capabilities().stream().sorted().toList())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(items, result.nextCursor()));
  }

  @PutMapping("/by-membership/{membershipId}/capabilities")
  public ResponseEntity<Void> replace(@PathVariable String rankingId, @PathVariable String membershipId,
      HttpServletRequest request, @AuthenticationPrincipal(expression = "userid") UUID actorId,
      @AuthenticationPrincipal UserDetails principal) {
    var actor = actor(actorId, principal);
    var rank = scope(rankingId);
    var membership = scope(membershipId);
    roster.replace(actor, rank, membership, capabilities(request));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private PageOptions page(HttpServletRequest request) {
    try {
      if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1)
        throw new InvalidRankingAccessPageException();
    } catch (IOException unreadable) {
      throw new InvalidRankingAccessPageException();
    }
    var query = request.getQueryString();
    if (query == null) return new PageOptions(null, ManageMembershipRosterUseCase.DEFAULT_LIMIT);
    if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) throw new InvalidRankingAccessPageException();
    UUID after = null;
    Integer limit = null;
    for (var part : query.split("&", -1)) {
      if (part.startsWith("after=")) {
        String value = part.substring(6);
        if (after != null || !UUID_PATTERN.matcher(value).matches()) throw new InvalidRankingAccessPageException();
        after = UUID.fromString(value);
      } else if (part.startsWith("limit=")) {
        String value = part.substring(6);
        if (limit != null || !LIMIT.matcher(value).matches()) throw new InvalidRankingAccessPageException();
        try { limit = Integer.parseInt(value); }
        catch (NumberFormatException malformed) { throw new InvalidRankingAccessPageException(); }
      } else {
        throw new InvalidRankingAccessPageException();
      }
    }
    return new PageOptions(after, limit == null ? ManageMembershipRosterUseCase.DEFAULT_LIMIT : limit);
  }

  private Set<RankingCapability> capabilities(HttpServletRequest request) {
    if (request.getQueryString() != null || request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new InvalidRankingCapabilitiesException();
    try {
      byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (raw.length == 0 || raw.length > MAX_BODY_BYTES) throw new InvalidRankingCapabilitiesException();
      JsonNode body = BODY.readValue(raw);
      if (body == null || !body.isObject() || body.size() != 1 || !body.path("capabilities").isArray()
          || body.get("capabilities").size() > MAX_GRANT_VALUES) throw new InvalidRankingCapabilitiesException();
      var grants = EnumSet.noneOf(RankingCapability.class);
      for (var item : body.get("capabilities")) {
        if (!item.isTextual()) throw new InvalidRankingCapabilitiesException();
        grants.add(RankingCapability.valueOf(item.textValue()));
      }
      return grants;
    } catch (IOException | IllegalArgumentException malformed) {
      throw new InvalidRankingCapabilitiesException();
    }
  }

  private RankingActor actor(UUID actorId, UserDetails principal) {
    if (actorId == null || principal == null || !principal.isEnabled() || !principal.isAccountNonLocked()
        || !principal.isAccountNonExpired() || !principal.isCredentialsNonExpired())
      throw new RankingAccessDeniedException();
    return new RankingActor(actorId, true);
  }

  private UUID scope(String value) {
    if (value == null || !UUID_PATTERN.matcher(value).matches()) throw new RankingAccessDeniedException();
    return UUID.fromString(value);
  }

  private record PageOptions(UUID after, int limit) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PageResponse(List<MemberResponse> items, UUID nextCursor) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record MemberResponse(UUID membershipId, String displayName, List<RankingCapability> capabilities) {}

  @ExceptionHandler(RankingAccessDeniedException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return error(HttpStatus.NOT_FOUND, "ranking.not_found");
  }

  @ExceptionHandler(InvalidRankingAccessPageException.class)
  public ResponseEntity<Map<String, String>> invalidPage() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_page");
  }

  @ExceptionHandler(InvalidRankingCapabilitiesException.class)
  public ResponseEntity<Map<String, String>> invalidCapabilities() {
    return error(HttpStatus.BAD_REQUEST, "ranking.invalid_capabilities");
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
  }
}
