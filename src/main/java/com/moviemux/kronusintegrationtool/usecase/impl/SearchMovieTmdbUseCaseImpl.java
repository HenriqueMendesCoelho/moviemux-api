package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.MovieSearch;
import com.moviemux.kronusintegrationtool.usecase.SearchMovieTmdbUseCase;
import org.springframework.stereotype.Component;

@Component
public class SearchMovieTmdbUseCaseImpl implements SearchMovieTmdbUseCase {

	private final KronusCatalogRepository repository;

    public SearchMovieTmdbUseCaseImpl(KronusCatalogRepository repository) {
        this.repository = repository;
    }

	@Override
	public MovieSearch searchByName(String name, Integer page, String language) {
		return repository.searchByName(name, page, language, false);
	}

	@Override
	public MovieSearch moviesPopular(Integer page) {
		return repository.moviesPopular(page);
	}

	@Override
	public MovieSearch moviesNowPlaying(Integer page) {
		return repository.moviesNowPlaying(page);
	}

	@Override
	public MovieSearch moviesUpcoming(Integer page) {
		return repository.moviesUpcoming(page);
	}

	@Override
	public MovieSearch moviesTopRated(Integer page) {
		return repository.moviesTopRated(page);
	}

	@Override
	public MovieSearch moviesRecommendations(Long movieTmdbId, Integer page) {
		return repository.moviesRecommendations(movieTmdbId, page);
	}

	@Override
	public MovieSearch moviesSimilar(Long movieTmdbId, Integer page) {
		return repository.moviesSimilar(movieTmdbId, page);
	}

	@Override
	public MovieSearch discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear, String withGenres,
			String withoutGenres) {
		return repository.discoverMovies(sortByParam, page, primaryReleaseYear, withGenres, withoutGenres);
	}

}
