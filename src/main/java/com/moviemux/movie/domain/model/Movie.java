package com.moviemux.movie.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;




import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Movie {




	private UUID id;


	private String portugueseTitle;


	private String englishTitle;


	private String originalTitle;


	private String director;


	private String urlImage;


	private String portugueseUrlTrailer;


	private String englishUrlTrailer;


	private String description;


	private LocalDate releaseDate;


	private Long tmdbId;


	private String imdbId;


	private Integer runtime;


	private boolean showNotes;


	private List<MovieNote> notes;



	private List<MovieGenre> genres;



	private MovieActor user;



	private OffsetDateTime createdAt;



	private OffsetDateTime updatedAt;



	private MovieDiscord movieDiscord;
}
