package com.moviemux.wishlist.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.domain.command.UpdateWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.MovieWishlistRepository;
import com.moviemux.wishlist.domain.repository.MoviesWishlistsRepository;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class UpdateWishlistUseCase {

  private static final int LIMIT = 100;

  private final WishlistRepository repository;

  private final MovieWishlistRepository movieWishlistRepository;

  private final MoviesWishlistsRepository moviesWishlistsRepository;

  private final KronusCatalogRepository kitRepository;

  public Wishlist execute(UpdateWishlistCommand command)
      throws WishlistNotFoundException,
          WishlistMovieAlreadyExistsException,
          UserNotAuthorizedException {
    Wishlist userWishlist = command.wishlist();
    UUID userId = command.actorId();
    Wishlist userWishlistToUpdate = repository.findById(userWishlist.getId()).orElse(null);

    if (userWishlistToUpdate == null || userWishlistToUpdate.getMoviesWishlists().size() > LIMIT) {
      throw new WishlistNotFoundException();
    }

    if (!userWishlistToUpdate.getUser().getId().equals(userId)) {
      throw new UserNotAuthorizedException();
    }

    List<MovieWishlist> moviesToCheck =
        userWishlistToUpdate.getMoviesWishlists().stream()
            .map(MoviesWishlists::getMovieWishlist)
            .toList();
    createIfNotExistMovieWishlist(moviesToCheck);

    removeMoviesIfNeeded(userWishlistToUpdate, userWishlist);
    userWishlistToUpdate =
        userWishlistToUpdate.toBuilder()
            .name(userWishlist.getName())
            .moviesWishlists(setOrder(userWishlist.getMoviesWishlists()))
            .shareable(userWishlist.isShareable())
            .updatedAt(OffsetDateTime.now())
            .build();

    try {
      moviesWishlistsRepository.saveAll(userWishlistToUpdate.getMoviesWishlists());
      return repository.save(userWishlistToUpdate);
    } catch (WishlistPersistenceConflict e) {
      log.error("Error to update wishlist: ", e);
      throw new WishlistMovieAlreadyExistsException(userWishlist.getName());
    }
  }

  private List<MovieWishlist> createIfNotExistMovieWishlist(List<MovieWishlist> movies) {
    List<MovieWishlist> result = new ArrayList<>();
    for (MovieWishlist movie : movies) {
      MovieWishlist exists = movieWishlistRepository.findById(movie.getTmdbId()).orElse(null);

      if (exists != null) {
        result.add(exists);
        continue;
      }

      MovieSummary movieCreate = kitRepository.movieSummary(movie.getTmdbId());

      if (movieCreate == null) {
        continue;
      }

      result.add(
          movieWishlistRepository.save(
              MovieWishlist.builder()
                  .tmdbId(movieCreate.getTmdbId())
                  .title(movieCreate.getPortugueseTitle())
                  .titleEnglish(movieCreate.getEnglishTitle())
                  .urlImage(
                      movieCreate.getUrlImagePortuguese() != null
                          ? movieCreate.getUrlImagePortuguese()
                          : movieCreate.getUrlImageEnglish())
                  .releaseDate(movieCreate.getReleaseDate())
                  .build()));
    }
    return result;
  }

  private List<MoviesWishlists> setOrder(List<MoviesWishlists> moviesToAdd) {
    for (int i = 0; i < moviesToAdd.size(); i++) {
      moviesToAdd.get(i).setWishlistOrder(i + 1);
    }

    return moviesToAdd;
  }

  private void removeMoviesIfNeeded(Wishlist userWishlistToUpdate, Wishlist request) {
    List<MoviesWishlists> moviesToRemove =
        userWishlistToUpdate.getMoviesWishlists().stream()
            .filter(
                wU ->
                    request.getMoviesWishlists().stream()
                        .noneMatch(
                            req ->
                                req.getMovieWishlist()
                                    .getTmdbId()
                                    .equals(wU.getMovieWishlist().getTmdbId())))
            .toList();

    if (CollectionUtils.isEmpty(moviesToRemove)) {
      return;
    }
    userWishlistToUpdate.setMoviesWishlists(
        userWishlistToUpdate.getMoviesWishlists().stream()
            .filter(item -> !moviesToRemove.contains(item))
            .toList());
    moviesWishlistsRepository.deleteAll(moviesToRemove);
  }
}
