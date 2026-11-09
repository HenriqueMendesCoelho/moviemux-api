package com.moviemux.movie.domain.command;
import com.moviemux.movie.domain.model.Movie;
import java.util.UUID;
public record DeleteMovieNoteCommand(UUID userId, UUID movieId) { }
