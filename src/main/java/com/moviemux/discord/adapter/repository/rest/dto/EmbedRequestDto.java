package com.moviemux.discord.adapter.repository.rest.dto;

import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieNote;
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

	private static final String URL_REDIRECT = "https://www.moviemux.com";
	private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
	private static final Integer BLUE_COLOR_CODE = 3447003;
	private static final String DELETED_USER_RED_CIRCLE_EMOJI = "\uD83D\uDD34";

	private String title;
	private Image image;
	private int color;
	private String url;
	private Footer footer;
	private List<Field> fields;
	private OffsetDateTime timestamp;

	public EmbedRequestDto(Movie movie) {
		title = movie.isShowNotes()
				? "%s | Nota: %s".formatted(movie.getPortugueseTitle(), getAvgNotes(movie))
				: movie.getPortugueseTitle();
		image = new Image(movie.getUrlImage());
		color = BLUE_COLOR_CODE;
		url = String.format("%s/movie/%s", URL_REDIRECT, movie.getId());
		footer = movie.getUser() == null
				? new Footer("%s Cadastrado por: Usuário desconhecido".formatted(DELETED_USER_RED_CIRCLE_EMOJI))
				: new Footer("Cadastrado por: %s".formatted(movie.getUser().getName()));
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
	public static class Field {
		private String name;
		private String value;
		private boolean inline;

		public Field(MovieNote note) {
			name = note.getUser().getName();
			value = Objects.toString(note.getNote(), "");
			inline = true;
		}

		public static Comparator<Field> comparator() {
			return Comparator.comparingInt(Field::getValueInt).reversed().thenComparing(Field::getName);
		}

		public int getValueInt() {
			return Integer.parseInt(this.getValue());
		}
	}

	@Data
	@AllArgsConstructor
	public static class Footer {
		private String text;
	}

	@Data
	@AllArgsConstructor
	public static class Image {
		private String url;
	}
}
