package com.moviemux.rankingcontent.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.usecase.ListRankingEntriesUseCase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/rankings")
public class RankingEntriesController {
  private final ListRankingEntriesUseCase useCase;
  public RankingEntriesController(ListRankingEntriesUseCase useCase) {this.useCase=useCase;}
  @GetMapping("/{rankingId}/entries")
  public ResponseEntity<PageResponse> list(@PathVariable UUID rankingId,
      @RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,
      @AuthenticationPrincipal(expression="userid") UUID actorUserId,@AuthenticationPrincipal UserDetails principal) {
    if(principal==null || actorUserId==null)throw new RankingEntriesDeniedException();
    var actor=new RankingActor(actorUserId,principal.isEnabled() && principal.isAccountNonLocked()
        && principal.isAccountNonExpired() && principal.isCredentialsNonExpired());
    var page=useCase.execute(actor,rankingId,after,limit);
    var items=page.items().stream().map(e->new EntryResponse(e.entryId(),e.movieId(),e.tmdbId(),e.originalTitle(),
        e.englishTitle(),e.portugueseTitle(),e.urlImage(),e.releaseDate(),e.runtime(),e.createdAt())).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(items,page.nextCursor()));
  }
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record EntryResponse(UUID entryId,UUID movieId,Long tmdbId,String originalTitle,String englishTitle,
      String portugueseTitle,String urlImage,LocalDate releaseDate,Integer runtime,OffsetDateTime createdAt) {}
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record PageResponse(List<EntryResponse> items,UUID nextCursor) {}
  @ExceptionHandler(RankingEntriesDeniedException.class)
  public ResponseEntity<Map<String,String>> notFound(){return error(HttpStatus.NOT_FOUND,"ranking.not_found");}
  @ExceptionHandler({InvalidRankingEntriesPageException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> invalidPage(){return error(HttpStatus.BAD_REQUEST,"ranking.invalid_page");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class})
  public ResponseEntity<Map<String,String>> operationFailed(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"ranking.operation_failed");}
  private ResponseEntity<Map<String,String>> error(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
