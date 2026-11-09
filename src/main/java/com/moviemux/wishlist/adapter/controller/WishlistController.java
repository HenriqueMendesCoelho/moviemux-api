package com.moviemux.wishlist.adapter.controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.controller.dto.MoviesAlreadyRatedResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

public interface WishlistController {

  List<WishlistResponseDto> getUserWishlists(UserTokenDto request);

  WishlistResponseDto findById(UUID wishlistId, UserTokenDto user) throws WishlistNotFoundException;

  WishlistResponseDto createUserWishlist(String name, UserTokenDto user)
      throws WishlistDuplicatedException, WishlistUserReachedLimitException;

  WishlistResponseDto updateUserWishlist(@Valid WishlistRequestDto request, UserTokenDto user)
      throws WishlistNotFoundException,
          WishlistMovieAlreadyExistsException,
          UserNotAuthorizedException;

  WishlistResponseDto addMovieToWishlist(UUID wishlistId, UserTokenDto user, Long tmdbId)
      throws WishlistNotFoundException, WishlistMovieAlreadyExistsException;

  void deleteUserWishlist(UUID wishlistId, UserTokenDto user);

  MoviesAlreadyRatedResponseDto searchMoviesAlreadyRatedImpl(UUID wishlistId);
}
