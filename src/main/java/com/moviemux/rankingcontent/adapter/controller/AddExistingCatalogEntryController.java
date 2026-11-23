package com.moviemux.rankingcontent.adapter.controller;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.AddExistingCatalogEntryUseCase;
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
@RequestMapping({"/movies/collections", "/rankings"})
public class AddExistingCatalogEntryController {
  private final AddExistingCatalogEntryUseCase useCase;
  public AddExistingCatalogEntryController(AddExistingCatalogEntryUseCase useCase){this.useCase=useCase;}
  @PostMapping("/{rankingId}/entries/catalog")
  public ResponseEntity<EntryResponse> add(@PathVariable UUID rankingId,@RequestBody CatalogRequest body,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal) {
    if(actorId==null || principal==null)throw new RankingEntriesDeniedException();
    if(body==null || body.movieId()==null)throw new InvalidCatalogEntryException();
    var actor=new RankingActor(actorId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var result=useCase.execute(actor,rankingId,body.movieId());
    return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
        .body(new EntryResponse(result.entryId()));
  }
  public record CatalogRequest(UUID movieId) {}
  public record EntryResponse(UUID entryId) {}
  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound(){return error(HttpStatus.NOT_FOUND,"ranking.not_found");}
  @ExceptionHandler(ExpiredEntryRecoveryException.class)
  public ResponseEntity<Map<String,String>> recoveryExpired(){return error(HttpStatus.CONFLICT,"ranking.entry_recovery_expired");}
  @ExceptionHandler(ArchivedCatalogEntryException.class)
  public ResponseEntity<Map<String,String>> archived(){return error(HttpStatus.CONFLICT,"ranking.archived_read_only");}
  @ExceptionHandler({InvalidCatalogEntryException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"ranking.invalid_catalog_entry");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> failed(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
