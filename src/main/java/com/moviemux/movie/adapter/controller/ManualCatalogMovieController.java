package com.moviemux.movie.adapter.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.movie.domain.exception.InvalidManualCatalogMovieException;
import com.moviemux.movie.domain.exception.ManualCatalogCommandConflictException;
import com.moviemux.movie.domain.exception.ManualCatalogForbiddenException;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Admin;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.usecase.ManageManualCatalogMovieUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

import static com.moviemux.movie.domain.model.ManualCanonicalMovie.MAX_GENRES;

@RestController
@PreAuthorize("hasRole('ADM')")
@RequestMapping("/restricted/movies/catalog/manual")
public class ManualCatalogMovieController {
  private static final int MAX_BYTES=32768;
  private static final Pattern UUID4=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern DATE=Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Set<String> FIELDS=Set.of("command_id","portuguese_title","english_title","original_title",
      "director","description","release_date","runtime","genre_ids");
  private static final com.fasterxml.jackson.databind.ObjectReader BODY=new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ManageManualCatalogMovieUseCase catalog;

  public ManualCatalogMovieController(ManageManualCatalogMovieUseCase catalog){this.catalog=catalog;}

  @GetMapping
  public ResponseEntity<Void> proof(HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal) {
    var actor=actor(actorId,principal);
    queryless(request);
    try {
      if(request.getContentLengthLong()>0 || request.getInputStream().read()!=-1)throw invalidPayload();
    } catch(IOException unreadable){throw invalidPayload();}
    catalog.requireCurrentAdmin(actor);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @PostMapping
  public ResponseEntity<MovieResponse> create(HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID actorId,@AuthenticationPrincipal UserDetails principal) {
    var result=catalog.create(actor(actorId,principal),parse(request));
    return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore())
        .body(new MovieResponse(result.movieId()));
  }

  private Command parse(HttpServletRequest request) {
    queryless(request);
    if(request.getContentLengthLong()>MAX_BYTES)throw invalidPayload();
    try {
      byte[] bytes=request.getInputStream().readNBytes(MAX_BYTES+1);
      if(bytes.length==0 || bytes.length>MAX_BYTES)throw invalidPayload();
      JsonNode body=BODY.readValue(bytes);
      if(body==null || !body.isObject())throw invalidPayload();
      for(var names=body.fieldNames();names.hasNext();)if(!FIELDS.contains(names.next()))throw invalidPayload();
      String command=text(body,"command_id");
      if(command==null || !UUID4.matcher(command).matches())throw invalidPayload();
      String date=text(body,"release_date");
      if(date!=null && !DATE.matcher(date).matches())throw invalidPayload();
      return new Command(UUID.fromString(command),text(body,"portuguese_title"),text(body,"english_title"),
          text(body,"original_title"),text(body,"director"),text(body,"description"),date==null?null:LocalDate.parse(date),
          runtime(body.path("runtime")),genres(body.path("genre_ids")));
    } catch(IOException | DateTimeParseException unreadable){throw invalidPayload();}
  }

  private static String text(JsonNode body,String field) {
    var value=body.path(field);
    if(value.isMissingNode() || value.isNull())return null;
    if(!value.isTextual())throw invalidPayload();
    return value.textValue();
  }

  private static Integer runtime(JsonNode value) {
    if(value.isMissingNode() || value.isNull())return null;
    if(!value.isIntegralNumber() || !value.canConvertToInt())throw invalidPayload();
    return value.intValue();
  }

  private static List<Long> genres(JsonNode value) {
    if(value.isMissingNode())return List.of();
    if(!value.isArray() || value.size()>MAX_GENRES)throw invalidPayload();
    var result=new ArrayList<Long>(value.size());
    for(var id:value) {
      if(!id.isIntegralNumber() || !id.canConvertToLong())throw invalidPayload();
      result.add(id.longValue());
    }
    return List.copyOf(result);
  }

  private static void queryless(HttpServletRequest request){
    if(request.getQueryString()!=null)throw invalidPayload();
  }

  private Admin actor(UUID id,UserDetails principal) {
    if(id==null || principal==null || !principal.isEnabled() || !principal.isAccountNonLocked()
        || !principal.isAccountNonExpired() || !principal.isCredentialsNonExpired())
      throw new ManualCatalogForbiddenException();
    return new Admin(id,true);
  }

  public record MovieResponse(UUID movieId) {}
  private static InvalidManualCatalogMovieException invalidPayload(){return new InvalidManualCatalogMovieException();}

  @ExceptionHandler(InvalidManualCatalogMovieException.class)
  public ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"catalog.invalid_manual_movie");}
  @ExceptionHandler(ManualCatalogForbiddenException.class)
  public ResponseEntity<Map<String,String>> forbidden(){return error(HttpStatus.FORBIDDEN,"catalog.manual_forbidden");}
  @ExceptionHandler(ManualCatalogCommandConflictException.class)
  public ResponseEntity<Map<String,String>> conflict(){return error(HttpStatus.CONFLICT,"catalog.manual_command_conflict");}
  @ExceptionHandler({DataAccessException.class,TransactionException.class,IllegalStateException.class})
  public ResponseEntity<Map<String,String>> failure(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"catalog.operation_failed");}

  private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
  }
}
