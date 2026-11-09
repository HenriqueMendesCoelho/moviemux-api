package com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.*;
import com.moviemux.kronusintegrationtool.domain.*;
import com.moviemux.kronusintegrationtool.domain.model.CatalogGenre;

/** Remote DTO conversion is adapter behavior, not a domain responsibility. */
public final class CatalogResponseMapper {
    private CatalogResponseMapper() { }
    public static CatalogGenre genre(MovieGenreResponseDto value) {
        return new CatalogGenre(value.getId(), value.getName());
    }
    public static MovieSummary summary(MovieSummaryResponseDto value) {
        return MovieSummary.builder().tmdbId(value.getTmdbId()).imdbId(value.getImdbId())
                .portugueseTitle(value.getPortugueseTitle()).englishTitle(value.getEnglishTitle())
                .originalTitle(value.getOriginalTitle()).director(value.getDirector())
                // Preserve the existing transport mapping; changing this requires a separate behavior decision.
                .urlImageEnglish(value.getUrlImagePortuguese()).portugueseUrlTrailer(value.getPortugueseUrlTrailer())
                .englishUrlTrailer(value.getEnglishUrlTrailer()).genres(value.getGenres())
                .description(value.getDescription()).releaseDate(value.getReleaseDate()).runtime(value.getRuntime()).build();
    }
    public static MovieSearch search(MovieSearchResponseDto value) {
        return MovieSearch.builder().page(value.getPage()).totalPages(value.getTotalPages()).totalResults(value.getTotalResults())
                .results(value.getResults().stream().map(CatalogResponseMapper::result).toList()).build();
    }
    public static ResultSearch result(ResultSearchResponseDto value) {
        return ResultSearch.builder().adult(value.isAdult()).backdropPath(value.getBackdropPath()).genreIds(value.getGenreIds())
                .id(value.getId()).originalLanguage(value.getOriginalLanguage()).originalTitle(value.getOriginalTitle())
                .overview(value.getOverview()).popularity(value.getPopularity()).posterPath(value.getPosterPath())
                .releaseDate(value.getReleaseDate()).title(value.getTitle()).video(value.isVideo())
                .voteAverage(value.getVoteAverage()).voteCount(value.getVoteCount()).build();
    }
    public static Credit credits(CreditResponseDto value) {
        return Credit.builder().cast(value.getCast().stream().map(CatalogResponseMapper::cast).toList())
                .crew(value.getCrew().stream().map(CatalogResponseMapper::cast).toList()).build();
    }
    public static Credit.Cast cast(CreditResponseDto.CastResponseDto value) {
        return Credit.Cast.builder().knownForDepartment(value.getKnownForDepartment()).name(value.getName())
                .originalName(value.getOriginalName()).profilePath(value.getProfilePath()).character(value.getCharacter())
                .department(value.getDepartment()).job(value.getJob()).build();
    }
    public static SendMailTemplateRequestDto mail(SendMailTemplate value) {
        return new SendMailTemplateRequestDto(value.getFrom(), value.getTo(), value.getTemplateId(),
                value.getSubject(), value.getParams());
    }
}
