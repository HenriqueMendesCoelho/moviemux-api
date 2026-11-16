package com.moviemux.user.application.spring.controller;

import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.UserEmailRequestDto;
import com.moviemux.user.adapter.controller.dto.UserRedefinePasswordByKeyRequestDto;
import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import com.moviemux.user.adapter.controller.dto.UserResponseDto;
import com.moviemux.user.domain.exception.DuplicatedUserException;
import com.moviemux.user.domain.exception.InviteNotValidException;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyNotFound;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/public/user")
@Slf4j
@RequiredArgsConstructor
public class UserPublicSpringController {

  private final UserController controller;

  @PostMapping
  public ResponseEntity<?> createUser(@RequestBody @Valid UserRequestDto user) {
    UserResponseDto response;
    try {
      response = controller.createUser(user);
      return ResponseEntity.ok(response);
    } catch (DuplicatedUserException | InviteNotValidException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", true, "status", 400, "message", e.getMessage()));
    }
  }

  // User RedefinePasswordKey
  @PostMapping("/password/reset")
  public ResponseEntity<?> createRedefinePassowordKey(@RequestBody UserEmailRequestDto request) {
    controller.createRedefinePasswordKey(request);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/password/{key}/reset")
  public ResponseEntity<?> createRedefinePassowordKey(
      @PathVariable String key, @RequestBody UserRedefinePasswordByKeyRequestDto request) {
    try {
      controller.redefinePasswordByKey(request, key);
    } catch (UserRedefinePasswordKeyNotFound
        | UserRedefinePasswordKeyInvalid
        | UserNotAuthorizedException e) {
      log.error(e.getMessage());
      return ResponseEntity.status(403)
          .body(Map.of("error", true, "status", 403, "message", "Not Authorized"));
    }
    return ResponseEntity.ok().build();
  }
}
