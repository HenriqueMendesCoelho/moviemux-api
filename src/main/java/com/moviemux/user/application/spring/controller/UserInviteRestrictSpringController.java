package com.moviemux.user.application.spring.controller;

import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.InviteResponseDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/restricted/user/invite")
@RequiredArgsConstructor
public class UserInviteRestrictSpringController {

  private final UserController controller;

  @GetMapping
  @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<List<InviteResponseDto>> listAllInvites() {
    return ResponseEntity.ok(controller.getAllInvites());
  }

  @PostMapping
  @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<InviteResponseDto> createInvite() {
    return ResponseEntity.ok(controller.createUserInvite());
  }

  @DeleteMapping("/{code}/delete")
  @PreAuthorize("hasRole('ADM')")
  public ResponseEntity<?> deleteInvite(@PathVariable String code) {
    controller.deleteInvite(code);
    return ResponseEntity.ok().build();
  }
}
