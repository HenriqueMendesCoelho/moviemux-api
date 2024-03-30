package com.kronusboss.cine.discord.adapter.repository.rest.dto;

import com.kronusboss.cine.movie.domain.Movie;
import com.kronusboss.cine.movie.domain.MovieNote;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.OffsetDateTime;
import java.util.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EmbedRequestDto {

	private static final String URL_REDIRECT = "https://www.cine.kronusboss.com";
	private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
	private static final Integer BLUE_COLOR_CODE = 3447003;

	public String title;
	public Image image;
	public int color;
	public String url;
	public Footer footer;
	public List<Field> fields;
	public OffsetDateTime timestamp;

	public EmbedRequestDto(Movie movie) {
		title = movie.isShowNotes()
				? "%s | Nota: %s".formatted(movie.getPortugueseTitle(), getAvgNotes(movie))
				: movie.getPortugueseTitle();
		image = new Image(movie.getUrlImage());
		color = BLUE_COLOR_CODE;
		url = String.format("%s/movie/%s", URL_REDIRECT, movie.getId());
		footer = new Footer("Cadastrado por: %s".formatted(movie.getUser().getName()));
		fields = CollectionUtils.isNotEmpty(movie.getNotes()) ? movie.getNotes()
				.stream()
				.map(Field::new)
				.sorted(Field.comparator())
				.toList() : new ArrayList<>();
		timestamp = movie.getCreatedAt();
	}

	private String getAvgNotes(Movie movie) {
		if (movie.getNotes() == null) {
			return "0.0";
		}

		List<Integer> notes = movie.getNotes().stream().map(MovieNote::getNote).toList();
		double avgNote = notes.stream().mapToDouble(d -> d).average().orElse(0.0);
		DECIMAL_FORMAT.setRoundingMode(RoundingMode.HALF_EVEN);

		return Objects.toString(DECIMAL_FORMAT.format(avgNote), "0.0");
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Field {
		public String name;
		public String value;
		public boolean inline;

		public Field(MovieNote note) {
			name = note.getUser().getName();
			value = Objects.toString(note.getNote(), "");
			inline = true;
		}

		public static Comparator<Field> comparator() {
			return Comparator.comparing(Field::getValue).reversed().thenComparing(Field::getName);
		}
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Footer {
		public String text;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Image {
		public String url;
	}
}
