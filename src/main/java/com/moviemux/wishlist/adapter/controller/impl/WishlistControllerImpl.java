package com.moviemux.wishlist.adapter.controller.impl;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.movie.domain.model.MovieCatalogEntry;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.controller.WishlistController;
import com.moviemux.wishlist.adapter.controller.WishlistQueryOperations;
import com.moviemux.wishlist.adapter.controller.WishlistUpdateOperations;
import com.moviemux.wishlist.adapter.controller.dto.MoviesAlreadyRatedResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.usecase.CreateUserWishlistUseCase;
import com.moviemux.wishlist.domain.usecase.DeleteUserWishlistUseCase;
import com.moviemux.wishlist.domain.usecase.SearchMoviesAlreadyRated;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

@Controller
public class WishlistControllerImpl implements WishlistController {

  @Autowired private WishlistQueryOperations searchUserWishlistUseCase;

  @Autowired private CreateUserWishlistUseCase createUserWishlistUseCase;

  @Autowired private WishlistUpdateOperations updateUserWishlistUseCase;

  @Autowired private DeleteUserWishlistUseCase deleteUserWishlistUseCase;

  @Autowired private SearchMoviesAlreadyRated searchMoviesAlreadyRated;

  @Override
  public List<WishlistResponseDto> getUserWishlists(UserTokenDto request) {
    List<Wishlist> response = searchUserWishlistUseCase.list(request.getId());
    return response.stream().map(WishlistResponseDto::new).collect(Collectors.toList());
  }

  @Override
  public WishlistResponseDto findById(UUID wishlistId, UserTokenDto user)
      throws WishlistNotFoundException {
    Wishlist response = searchUserWishlistUseCase.findById(wishlistId, user.getId());
    return new WishlistResponseDto(response);
  }

  @Override
  public WishlistResponseDto createUserWishlist(String name, UserTokenDto user)
      throws WishlistDuplicatedException, WishlistUserReachedLimitException {
    Wishlist response =
        createUserWishlistUseCase.execute(
            new com.moviemux.wishlist.domain.command.CreateWishlistCommand(name, user.getId()));
    return new WishlistResponseDto(response);
  }

  @Override
  public WishlistResponseDto updateUserWishlist(WishlistRequestDto request, UserTokenDto user)
      throws WishlistNotFoundException,
          WishlistMovieAlreadyExistsException,
          UserNotAuthorizedException {
    Wishlist response = updateUserWishlistUseCase.update(request.toDomain(), user.getId());
    return new WishlistResponseDto(response);
  }

  @Override
  public WishlistResponseDto addMovieToWishlist(UUID wishlistId, UserTokenDto user, Long tmdbId)
      throws WishlistNotFoundException, WishlistMovieAlreadyExistsException {
    Wishlist response =
        updateUserWishlistUseCase.addMovieToWishlist(wishlistId, user.getId(), tmdbId);
    return new WishlistResponseDto(response);
  }

  @Override
  public void deleteUserWishlist(UUID wishlistId, UserTokenDto user) {
    deleteUserWishlistUseCase.execute(
        new com.moviemux.wishlist.domain.command.DeleteWishlistCommand(wishlistId, user.getId()));
  }

  @Override
  public MoviesAlreadyRatedResponseDto searchMoviesAlreadyRatedImpl(UUID wishlistId) {
    List<MovieCatalogEntry> response = searchMoviesAlreadyRated.execute(wishlistId);
    return new MoviesAlreadyRatedResponseDto(response);
  }
}
