package com.moviemux.wishlist.application.spring.controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.adapter.util.CredentialUtil;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.controller.WishlistController;
import com.moviemux.wishlist.adapter.controller.dto.MoviesAlreadyRatedResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/wishlist")
public class SpringUserWishlistController {

  private final WishlistController controller;

  public SpringUserWishlistController(WishlistController controller) {
    this.controller = controller;
  }

  @GetMapping
  public ResponseEntity<List<WishlistResponseDto>> listUserWishlists(
      @RequestHeader("Authorization") String token) {
    UserTokenDto user = CredentialUtil.getUserFromToken(token);
    List<WishlistResponseDto> response = controller.getUserWishlists(user);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}")
  public ResponseEntity<?> findById(
      @RequestHeader("Authorization") String token, @PathVariable UUID id) {
    try {
      UserTokenDto user = CredentialUtil.getUserFromToken(token);
      WishlistResponseDto response = controller.findById(id, user);
      return ResponseEntity.ok(response);
    } catch (WishlistNotFoundException e) {
      return ResponseEntity.noContent().build();
    }
  }

  @GetMapping("/{id}/movies-rated")
  public ResponseEntity<?> findById(@PathVariable UUID id) {

    MoviesAlreadyRatedResponseDto response = controller.searchMoviesAlreadyRatedImpl(id);

    if (CollectionUtils.isEmpty(response.getMovieTmdbIds())) {
      return ResponseEntity.noContent().build();
    }

    return ResponseEntity.ok(response);
  }

  @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
  public ResponseEntity<?> createWishlist(
      @RequestHeader("Authorization") String token, @RequestParam(required = true) String name) {
    try {
      UserTokenDto user = CredentialUtil.getUserFromToken(token);
      return ResponseEntity.ok(controller.createUserWishlist(name, user));
    } catch (WishlistDuplicatedException | WishlistUserReachedLimitException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", true, "status", 400, "message", e.getMessage()));
    }
  }

  @PatchMapping(
      path = "/{id}",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
  public ResponseEntity<?> addMovieToWishlist(
      @RequestHeader("Authorization") String token,
      @PathVariable UUID id,
      @RequestParam(required = true) Long tmdbId) {
    try {
      UserTokenDto user = CredentialUtil.getUserFromToken(token);
      WishlistResponseDto response = controller.addMovieToWishlist(id, user, tmdbId);
      return ResponseEntity.ok(response);
    } catch (WishlistNotFoundException | WishlistMovieAlreadyExistsException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", true, "status", 400, "message", e.getMessage()));
    }
  }

  @PutMapping("/{id}/update")
  public ResponseEntity<?> updateWishlist(
      @RequestHeader("Authorization") String token,
      @PathVariable UUID id,
      @RequestBody @Valid WishlistRequestDto request) {
    try {
      UserTokenDto user = CredentialUtil.getUserFromToken(token);
      if (!id.equals(request.getId())) {
        throw new WishlistNotFoundException();
      }
      WishlistResponseDto response = controller.updateUserWishlist(request, user);
      return ResponseEntity.ok(response);
    } catch (WishlistNotFoundException
        | WishlistMovieAlreadyExistsException
        | UserNotAuthorizedException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", true, "status", 400, "message", e.getMessage()));
    }
  }

  @DeleteMapping("/{id}/delete")
  public ResponseEntity<?> deleteWishlist(
      @RequestHeader("Authorization") String token, @PathVariable UUID id) {
    UserTokenDto user = CredentialUtil.getUserFromToken(token);
    controller.deleteUserWishlist(id, user);
    return ResponseEntity.ok().build();
  }
}
