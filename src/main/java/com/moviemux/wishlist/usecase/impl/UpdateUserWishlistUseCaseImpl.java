package com.moviemux.wishlist.usecase.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.repository.MovieWishlistRepository;
import com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository;
import com.moviemux.wishlist.adapter.repository.WishlistRepository;
import com.moviemux.wishlist.domain.MovieWishlist;
import com.moviemux.wishlist.domain.MoviesWishlists;
import com.moviemux.wishlist.domain.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.Wishlist;
import com.moviemux.wishlist.usecase.UpdateUserWishlistUseCase;
import com.moviemux.wishlist.usecase.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.usecase.exception.WishlistNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateUserWishlistUseCaseImpl implements UpdateUserWishlistUseCase {

	private static final int LIMIT = 100;

	private final WishlistRepository repository;
	private final MovieWishlistRepository movieWishlistRepository;
	private final MoviesWishlistsRepository moviesWishlistsRepository;
	private final KronusIntegrationToolRepository kitRepository;

	@Override
	public Wishlist update(Wishlist userWishlist, UUID userId)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException, UserNotAuthorizedException {
		Wishlist userWishlistToUpdate = repository.findById(userWishlist.getId()).orElse(null);

		if (userWishlistToUpdate == null || userWishlistToUpdate.getMoviesWishlists().size() > LIMIT) {
			throw new WishlistNotFoundException();
		}

		if (!userWishlistToUpdate.getUser().getId().equals(userId)) {
			throw new UserNotAuthorizedException();
		}

		List<MovieWishlist> moviesToCheck = userWishlistToUpdate.getMoviesWishlists()
				.stream()
				.map(MoviesWishlists::getMovieWishlist)
				.toList();
		createIfNotExistMovieWishlist(moviesToCheck);

		removeMoviesIfNeeded(userWishlistToUpdate, userWishlist);
		userWishlistToUpdate = userWishlistToUpdate.toBuilder()
				.name(userWishlist.getName())
				.moviesWishlists(setOrder(userWishlist.getMoviesWishlists()))
				.shareable(userWishlist.isShareable())
				.updatedAt(OffsetDateTime.now())
				.build();

		try {
			moviesWishlistsRepository.saveAll(userWishlistToUpdate.getMoviesWishlists());
			return repository.save(userWishlistToUpdate);
		} catch (DataIntegrityViolationException e) {
			log.error("Error to update wishlist: ", e);
			throw new WishlistMovieAlreadyExistsException(userWishlist.getName());
		}
	}

	@Override
	public Wishlist addMovieToWishlist(UUID wishlistId, UUID userId, Long tmdbId)
			throws WishlistNotFoundException, WishlistMovieAlreadyExistsException {
		Wishlist userWishlistToUpdate = repository.findById(wishlistId).orElse(null);

		if (userWishlistToUpdate == null || userWishlistToUpdate.getMoviesWishlists().size() >= LIMIT) {
			throw new WishlistNotFoundException();
		}

		MovieWishlist movieToAdd = MovieWishlist.builder().tmdbId(tmdbId).build();
		List<MovieWishlist> movies = createIfNotExistMovieWishlist(List.of(movieToAdd));

		List<MoviesWishlists> moviesWishlistsUpdated = new ArrayList<MoviesWishlists>(
				userWishlistToUpdate.getMoviesWishlists());
		moviesWishlistsUpdated.addAll(createMoviesWishlists(wishlistId, movies));

		userWishlistToUpdate.setUpdatedAt(OffsetDateTime.now());

		try {
			List<MoviesWishlists> list = moviesWishlistsRepository.saveAll(setOrder(moviesWishlistsUpdated));
			userWishlistToUpdate.setMoviesWishlists(list);
			return repository.save(userWishlistToUpdate);
		} catch (DataIntegrityViolationException e) {
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

			result.add(movieWishlistRepository.saveAndFlush(new MovieWishlist(movieCreate)));
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
				.map(m -> MoviesWishlists.builder()
						.id(new MoviesWishlistsKey(wishlistId, m.getTmdbId()))
						.wishlist(Wishlist.builder().id(wishlistId).build())
						.movieWishlist(MovieWishlist.builder().tmdbId(m.getTmdbId()).build())
						.build())
				.toList();
	}

	private void removeMoviesIfNeeded(Wishlist userWishlistToUpdate, Wishlist request) {
		List<MoviesWishlists> moviesToRemove = userWishlistToUpdate.getMoviesWishlists()
				.stream()
				.filter(wU -> request.getMoviesWishlists()
						.stream()
						.noneMatch(req -> req.getMovieWishlist().getTmdbId().equals(wU.getMovieWishlist().getTmdbId())))
				.toList();

		if (CollectionUtils.isEmpty(moviesToRemove)) {
			return;
		}
		userWishlistToUpdate.getMoviesWishlists().removeAll(moviesToRemove);
		moviesWishlistsRepository.deleteAll(moviesToRemove);
	}

}
