package com.moviemux.rankingcontent.adapter.controller;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import com.moviemux.rankingcontent.domain.usecase.EntryRecoveryUseCase;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestController
@RequestMapping("/rankings/{rankingId}/entries/{entryId}")
public class EntryRecoveryController {
  private final EntryRecoveryUseCase useCase;
  public EntryRecoveryController(EntryRecoveryUseCase useCase){this.useCase=useCase;}
  @GetMapping("/removal-preview")
  public ResponseEntity<StateResponse> preview(@PathVariable UUID rankingId,@PathVariable UUID entryId,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal){
    return response(useCase.preview(actor(actorId,principal),rankingId,entryId));
  }
  @DeleteMapping
  public ResponseEntity<StateResponse> remove(@PathVariable UUID rankingId,@PathVariable UUID entryId,@RequestBody RemovalRequest body,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal){
    var actor=actor(actorId,principal);
    if(body==null || body.confirm()==null || !body.confirm().isBoolean() || !body.confirm().booleanValue()
        || body.expectedVersion()==null || !body.expectedVersion().isIntegralNumber() || !body.expectedVersion().canConvertToLong())throw new InvalidEntryRecoveryException();
    return response(useCase.remove(actor,rankingId,entryId,body.expectedVersion().longValue(),true));
  }
  @PostMapping("/restore")
  public ResponseEntity<StateResponse> restore(@PathVariable UUID rankingId,@PathVariable UUID entryId,@RequestBody RestoreRequest body,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal){
    var actor=actor(actorId,principal);if(body==null || body.removalToken()==null)throw new InvalidEntryRecoveryException();
    return response(useCase.restore(actor,rankingId,entryId,body.removalToken()));
  }
  private RankingActor actor(UUID id,UserDetails principal){
    if(id==null || principal==null)throw new RankingEntriesDeniedException();
    return new RankingActor(id,principal.isEnabled() && principal.isAccountNonLocked() && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
  }
  private ResponseEntity<StateResponse> response(EntryRecoveryState s){
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StateResponse(s.entryId(),s.version(),s.removedAt(),s.expiresAt(),s.removalToken(),"ranking.entry_removal_permanent_after_retention",86400));
  }
  public record RemovalRequest(JsonNode expectedVersion,JsonNode confirm) {}
  public record RestoreRequest(UUID removalToken) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StateResponse(UUID entryId,long version,OffsetDateTime removedAt,OffsetDateTime expiresAt,UUID removalToken,String warningCode,long recoverySeconds) {}
  @ExceptionHandler(RankingEntriesDeniedException.class) public ResponseEntity<Map<String,String>> denied(){return error(404,"ranking.not_found");}
  @ExceptionHandler({InvalidEntryRecoveryException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<Map<String,String>> invalid(){return error(400,"ranking.invalid_recovery_request");}
  @ExceptionHandler(StaleEntryRecoveryException.class) public ResponseEntity<Map<String,String>> stale(){return error(409,"ranking.entry_state_stale");}
  @ExceptionHandler(ExpiredEntryRecoveryException.class) public ResponseEntity<Map<String,String>> expired(){return error(409,"ranking.entry_recovery_expired");}
  @ExceptionHandler(ArchivedEntryRecoveryException.class) public ResponseEntity<Map<String,String>> archived(){return error(409,"ranking.archived_read_only");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class}) public ResponseEntity<Map<String,String>> failed(){return error(500,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
