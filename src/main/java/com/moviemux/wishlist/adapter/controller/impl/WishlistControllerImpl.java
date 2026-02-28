package com.moviemux.wishlist.adapter.controller.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.wishlist.adapter.controller.WishlistController;
import com.moviemux.wishlist.adapter.controller.dto.MoviesAlreadyRatedResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.usecase.CreateUserWishlistUseCase;
import com.moviemux.wishlist.usecase.DeleteUserWishlistUseCase;
import com.moviemux.wishlist.usecase.SearchUserWishlistUseCase;
import com.moviemux.wishlist.usecase.UpdateUserWishlistUseCase;
import com.moviemux.wishlist.usecase.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.usecase.exception.WishlistNotFoundException;
import com.moviemux.wishlist.usecase.exception.WishlistUserReachedLimitException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.movie.domain.Movie;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.domain.Wishlist;
import com.moviemux.wishlist.usecase.SearchMoviesAlreadyRated;
import com.moviemux.wishlist.usecase.exception.WishlistDuplicatedException;

@Controller
@RequiredArgsConstructor
public class WishlistControllerImpl implements WishlistController {

	private final SearchUserWishlistUseCase searchUserWishlistUseCase;
	private final CreateUserWishlistUseCase createUserWishlistUseCase;
	private final UpdateUserWishlistUseCase updateUserWishlistUseCase;
	private final DeleteUserWishlistUseCase deleteUserWishlistUseCase;
	private final SearchMoviesAlreadyRated searchMoviesAlreadyRated;

	@Override
	public List<WishlistResponseDto> getUserWishlists(UserTokenDto request) {
		List<Wishlist> response = searchUserWishlistUseCase.list(request.getId());
		return response.stream().map(WishlistResponseDto::new).collect(Collectors.toList());
	}

	@Override
	public WishlistResponseDto findById(UUID wishlistId, UserTokenDto user) throws WishlistNotFoundException {
		Wishlist response = searchUserWishlistUseCase.findById(wishlistId, user.getId());
		return new WishlistResponseDto(response);
	}

	@Override
	public WishlistResponseDto createUserWishlist(String name, UserTokenDto user)
			throws WishlistDuplicatedException, WishlistUserReachedLimitException {
		Wishlist response = createUserWishlistUseCase.create(name, user.getId());
		return new WishlistResponseDto(response);
	}

	@Override
	public WishlistResponseDto updateUserWishlist(WishlistRequestDto request, UserTokenDto user)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException, UserNotAuthorizedException {
		Wishlist response = updateUserWishlistUseCase.update(request.toDomain(), user.getId());
		return new WishlistResponseDto(response);
	}

	@Override
	public WishlistResponseDto addMovieToWishlist(UUID wishlistId, UserTokenDto user, Long tmdbId)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException {
		Wishlist response = updateUserWishlistUseCase.addMovieToWishlist(wishlistId, user.getId(), tmdbId);
		return new WishlistResponseDto(response);
	}

	@Override
	public void deleteUserWishlist(UUID wishlistId, UserTokenDto user) {
		deleteUserWishlistUseCase.delete(wishlistId, user.getId());
	}

	@Override
	public MoviesAlreadyRatedResponseDto searchMoviesAlreadyRatedImpl(UUID wishlistId) {
		List<Movie> response = searchMoviesAlreadyRated.findMovies(wishlistId);
		return new MoviesAlreadyRatedResponseDto(response);
	}

}
