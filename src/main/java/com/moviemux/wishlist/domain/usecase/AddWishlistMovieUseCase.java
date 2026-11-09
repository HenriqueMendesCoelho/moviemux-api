package com.moviemux.wishlist.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.wishlist.domain.command.AddWishlistMovieCommand;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class AddWishlistMovieUseCase {

  private static final int LIMIT = 100;

  private final WishlistRepository repository;

  private final MovieWishlistRepository movieWishlistRepository;

  private final MoviesWishlistsRepository moviesWishlistsRepository;

  private final KronusCatalogRepository kitRepository;

  public Wishlist execute(AddWishlistMovieCommand command)
      throws WishlistNotFoundException, WishlistMovieAlreadyExistsException {
    UUID wishlistId = command.wishlistId();
    UUID userId = command.actorId();
    Long tmdbId = command.tmdbId();
    Wishlist userWishlistToUpdate = repository.findById(wishlistId).orElse(null);

    if (userWishlistToUpdate == null || userWishlistToUpdate.getMoviesWishlists().size() >= LIMIT) {
      throw new WishlistNotFoundException();
    }

    MovieWishlist movieToAdd = MovieWishlist.builder().tmdbId(tmdbId).build();
    List<MovieWishlist> movies = createIfNotExistMovieWishlist(List.of(movieToAdd));

    List<MoviesWishlists> moviesWishlistsUpdated =
        new ArrayList<MoviesWishlists>(userWishlistToUpdate.getMoviesWishlists());
    moviesWishlistsUpdated.addAll(createMoviesWishlists(wishlistId, movies));

    userWishlistToUpdate.setUpdatedAt(OffsetDateTime.now());

    try {
      List<MoviesWishlists> list =
          moviesWishlistsRepository.saveAll(setOrder(moviesWishlistsUpdated));
      userWishlistToUpdate.setMoviesWishlists(list);
      return repository.save(userWishlistToUpdate);
    } catch (WishlistPersistenceConflict e) {
      log.error("Error to add movie to wishlist: ", e);
      throw new WishlistMovieAlreadyExistsException(userWishlistToUpdate.getName());
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

  private List<MoviesWishlists> createMoviesWishlists(UUID wishlistId, List<MovieWishlist> movies) {
    return movies.stream()
        .map(
            m ->
                MoviesWishlists.builder()
                    .id(new MoviesWishlistsKey(wishlistId, m.getTmdbId()))
                    .wishlist(Wishlist.builder().id(wishlistId).build())
                    .movieWishlist(MovieWishlist.builder().tmdbId(m.getTmdbId()).build())
                    .build())
        .toList();
  }
}
