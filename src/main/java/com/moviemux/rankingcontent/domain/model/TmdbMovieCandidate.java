package com.moviemux.rankingcontent.domain.model;

import java.time.LocalDate;

/** Verified-film candidate; missing optional provider metadata remains null. */
public record TmdbMovieCandidate(long tmdbId, String imdbId, String portugueseTitle, String englishTitle,
    String originalTitle, String director, String urlImage, String portugueseUrlTrailer,
    String englishUrlTrailer, String description, LocalDate releaseDate, Integer runtime) {}
