package com.moviemux.movie.domain.model;
import java.util.List;
public record MoviePage(List<Movie> content, long totalElements, MovieQuery query) {
 public MoviePage { content=List.copyOf(content); }
}
