package com.moviemux.user.adapter.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.user.domain.registration.RegistrationFailure;
import com.moviemux.user.domain.registration.RegistrationMode;
import com.moviemux.user.domain.registration.RegistrationWaitlist;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
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
import static com.moviemux.user.domain.registration.RegistrationFailure.Reason.*;

@RestController
public class RegistrationController {
  private static final int MAX_BYTES=4096;
  private static final Pattern UUID_TEXT=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final com.fasterxml.jackson.databind.ObjectReader BODY=new ObjectMapper().readerFor(JsonNode.class)
      .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final RegistrationWaitlist service;
  private final Validator validator;
  public RegistrationController(RegistrationWaitlist service,Validator validator){this.service=service;this.validator=validator;}

  @GetMapping("/public/registration-mode")
  public ResponseEntity<ModeResponse> publicMode(HttpServletRequest request){
    bodyless(request);return ok(new ModeResponse(service.mode()));
  }

  @GetMapping("/restricted/registration-mode") @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<ModeResponse> adminMode(HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID id,@AuthenticationPrincipal UserDetails user){
    bodyless(request);return ok(new ModeResponse(service.adminMode(actor(id,user))));
  }

  @PutMapping("/restricted/registration-mode") @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<ModeResponse> changeMode(HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID id,@AuthenticationPrincipal UserDetails user){
    RegistrationMode mode;
    try{mode=RegistrationMode.valueOf(field(request,"mode"));}
    catch(IllegalArgumentException invalid){throw invalid();}
    return ok(new ModeResponse(service.changeMode(actor(id,user),mode)));
  }

  @PostMapping("/public/registration-waitlist")
  public ResponseEntity<Void> request(HttpServletRequest request){
    String email=field(request,"email").strip().toLowerCase(Locale.ROOT);
    if(email.codePointCount(0,email.length())>100 || !validator.validate(new EmailBody(email)).isEmpty()
        || email.codePoints().anyMatch(Character::isISOControl))throw invalid();
    service.request(email);
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping("/restricted/registration-waitlist") @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<PageResponse> list(HttpServletRequest request,
      @AuthenticationPrincipal(expression="userid") UUID id,@AuthenticationPrincipal UserDetails user){
    noBody(request);
    var params=request.getParameterMap();
    if(params.entrySet().stream().anyMatch(e->(!e.getKey().equals("after")&&!e.getKey().equals("limit"))
        ||e.getValue().length!=1))throw invalid();
    UUID after=request.getParameter("after")==null?null:uuid(request.getParameter("after"));
    int limit=20;
    if(request.getParameter("limit")!=null){
      String raw=request.getParameter("limit");
      if(!raw.matches("[1-9][0-9]?"))throw invalid();
      limit=Integer.parseInt(raw);
      if(limit>50)throw invalid();
    }
    var result=service.list(actor(id,user),after,limit);
    return ok(new PageResponse(result.items(),result.nextCursor()));
  }

  @PostMapping("/restricted/registration-waitlist/{requestId}/invite") @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<RegistrationWaitlist.Invitation> invite(HttpServletRequest request,@PathVariable String requestId,
      @AuthenticationPrincipal(expression="userid") UUID id,@AuthenticationPrincipal UserDetails user){
    bodyless(request);
    return ok(service.invite(actor(id,user),uuid(requestId)));
  }

  private static UUID actor(UUID id, UserDetails user){
    if(id==null || user==null || !user.isEnabled() || !user.isAccountNonLocked()
        || !user.isAccountNonExpired() || !user.isCredentialsNonExpired())
      throw new RegistrationFailure(FORBIDDEN);
    return id;
  }
  private static UUID uuid(String value){
    if(!UUID_TEXT.matcher(value).matches())throw invalid();
    return UUID.fromString(value);
  }
  private static String field(HttpServletRequest request,String name){
    if(request.getQueryString()!=null || request.getContentLengthLong()>MAX_BYTES)throw invalid();
    try{
      byte[] bytes=request.getInputStream().readNBytes(MAX_BYTES+1);
      if(bytes.length==0 || bytes.length>MAX_BYTES)throw invalid();
      JsonNode body=BODY.readValue(bytes);
      if(body==null || !body.isObject() || body.size()!=1 || !body.path(name).isTextual())throw invalid();
      return body.path(name).textValue();
    }catch(IOException unreadable){throw invalid();}
  }
  private static void bodyless(HttpServletRequest request){
    if(request.getQueryString()!=null)throw invalid();
    noBody(request);
  }
  private static void noBody(HttpServletRequest request){
    try{if(request.getContentLengthLong()>0 || request.getInputStream().read()!=-1)throw invalid();}
    catch(IOException unreadable){throw invalid();}
  }

  private static RegistrationFailure invalid(){return new RegistrationFailure(INVALID_REQUEST);}
  private static <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
  private record EmailBody(@Email @NotBlank String email){}
  public record ModeResponse(RegistrationMode mode){}
  public record PageResponse(java.util.List<RegistrationWaitlist.Item> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID nextCursor){}

  @ExceptionHandler(RegistrationFailure.class)
  public ResponseEntity<Map<String,String>> failure(RegistrationFailure error){
    var status=switch(error.reason()){
      case INVALID_REQUEST->HttpStatus.BAD_REQUEST;
      case FORBIDDEN->HttpStatus.FORBIDDEN;
      case REQUEST_NOT_FOUND->HttpStatus.NOT_FOUND;
      case MODE_CHANGED,INVITATION_IN_PROGRESS,INVITATION_REVOKED->HttpStatus.CONFLICT;
      case UNAVAILABLE->HttpStatus.SERVICE_UNAVAILABLE;
    };
    return failure(status,"registration."+error.reason().name().toLowerCase(Locale.ROOT));
  }
  @ExceptionHandler({DataAccessException.class,TransactionException.class,IllegalStateException.class})
  public ResponseEntity<Map<String,String>> unavailable(){
    return failure(HttpStatus.SERVICE_UNAVAILABLE,"registration.unavailable");
  }
  private static ResponseEntity<Map<String,String>> failure(HttpStatus status,String code){
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
  }
}
