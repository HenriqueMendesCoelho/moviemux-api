package com.moviemux.wishlist.adapter.controller;

import java.util.List;
import java.util.UUID;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.controller.dto.MoviesAlreadyRatedResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.usecase.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.usecase.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.usecase.exception.WishlistNotFoundException;
import com.moviemux.wishlist.usecase.exception.WishlistUserReachedLimitException;

import jakarta.validation.Valid;

public interface WishlistController {

	List<WishlistResponseDto> getUserWishlists(UserTokenDto request);

	WishlistResponseDto findById(UUID wishlistId, UserTokenDto user) throws WishlistNotFoundException;

	WishlistResponseDto createUserWishlist(String name, UserTokenDto user)
			throws WishlistDuplicatedException, WishlistUserReachedLimitException;

	WishlistResponseDto updateUserWishlist(@Valid WishlistRequestDto request, UserTokenDto user)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException, UserNotAuthorizedException;

	WishlistResponseDto addMovieToWishlist(UUID wishlistId, UserTokenDto user, Long tmdbId)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException;

	void deleteUserWishlist(UUID wishlistId, UserTokenDto user);

	MoviesAlreadyRatedResponseDto searchMoviesAlreadyRatedImpl(UUID wishlistId);

}
