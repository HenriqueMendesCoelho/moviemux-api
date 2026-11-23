package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Batch;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Pairing;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Status;
import com.moviemux.rankingcontent.domain.usecase.PreviewOwnRatingsBulkUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

import static com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.MAX_ITEMS;

@RestController
@RequestMapping({"/movies/collections/{destinationRankingId}/ratings/me/copy-bulk-preview",
    "/rankings/{destinationRankingId}/ratings/me/copy-bulk-preview"})
public class PreviewOwnRatingsBulkController {
  private static final int MAX_BODY_BYTES = 32*1024;
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final ObjectReader BODY = new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final PreviewOwnRatingsBulkUseCase copies;

  public PreviewOwnRatingsBulkController(PreviewOwnRatingsBulkUseCase copies) { this.copies=copies; }

  @PostMapping
  public ResponseEntity<PreviewResponse> preview(@PathVariable String destinationRankingId, HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID actorId, @AuthenticationPrincipal UserDetails principal) {
    if (actorId==null || principal==null || !principal.isEnabled() || !principal.isAccountNonLocked()
        || !principal.isAccountNonExpired() || !principal.isCredentialsNonExpired()
        || !UUID_PATTERN.matcher(destinationRankingId).matches()) throw new RankingEntriesDeniedException();
    var actor=new RankingActor(actorId,true);
    var batch=parse(request,UUID.fromString(destinationRankingId));
    var items=copies.preview(actor,batch).stream()
        .map(result->new ItemResponse(result.index(),result.status(),result.sourceScore())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PreviewResponse(items));
  }

  private Batch parse(HttpServletRequest request, UUID destination) {
    if (request.getQueryString()!=null || request.getContentLengthLong()>MAX_BODY_BYTES)
      throw new InvalidRatingCopyException();
    try {
      byte[] body=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);
      if (body.length==0 || body.length>MAX_BODY_BYTES) throw new InvalidRatingCopyException();
      JsonNode parsed=BODY.readValue(body);
      if (parsed==null || !parsed.isObject() || parsed.size()!=2) throw new InvalidRatingCopyException();
      UUID source=uuid(parsed,"source_ranking_id");
      var pairs=parsed.path("items");
      if (!pairs.isArray() || pairs.isEmpty() || pairs.size()>MAX_ITEMS) throw new InvalidRatingCopyException();
      var result=new ArrayList<Pairing>(pairs.size());
      for (var pair:pairs) {
        if (!pair.isObject() || pair.size()!=4) throw new InvalidRatingCopyException();
        result.add(new Pairing(uuid(pair,"source_entry_id"),uuid(pair,"source_round_id"),
            uuid(pair,"destination_entry_id"),uuid(pair,"destination_round_id")));
      }
      return new Batch(source,destination,List.copyOf(result));
    } catch (IOException malformed) {
      throw new InvalidRatingCopyException();
    }
  }

  private UUID uuid(JsonNode node,String key) {
    var value=node.path(key);
    if (!value.isTextual() || !UUID_PATTERN.matcher(value.textValue()).matches())
      throw new InvalidRatingCopyException();
    return UUID.fromString(value.textValue());
  }

  public record PreviewResponse(List<ItemResponse> items) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ItemResponse(int index, Status status, Integer sourceScore) {
    @Override public String toString() { return "ItemResponse[index="+index+", status="+status+"]"; }
  }

  @ExceptionHandler(InvalidRatingCopyException.class)
  public ResponseEntity<Map<String,String>> invalid() { return error(HttpStatus.BAD_REQUEST,"ranking.invalid_rating_copy"); }

  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound() { return error(HttpStatus.NOT_FOUND,"ranking.not_found"); }

  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> failure() { return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed"); }

  private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
  }
}
