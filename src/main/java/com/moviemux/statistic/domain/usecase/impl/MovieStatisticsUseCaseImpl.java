package com.moviemux.statistic.domain.usecase.impl;

import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Film;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Genre;
import com.moviemux.movie.domain.repository.MovieStatisticsReader;
import com.moviemux.statistic.domain.model.MovieStatistic;
import com.moviemux.statistic.domain.usecase.MovieStatisticsUseCase;
import java.text.DateFormatSymbols;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieStatisticsUseCaseImpl implements MovieStatisticsUseCase {

  private static final DecimalFormat DECIMAL_FORMAT =
      new DecimalFormat("0.00", new DecimalFormatSymbols(Locale.US));
  private static final int NUMBER_OF_MONTHS = 6;

  private final MovieStatisticsReader reader;
  private final Clock clock;

  @Override
  public MovieStatistic execute() {
    List<Film> movies = reader.movies();
    return MovieStatistic.builder()
        .totalNumberOfMovies(movies.size())
        .averageRate(averageRate())
        .averageRuntime(averageRuntime(movies))
        .moviesSixMonthsAgo(moviesSixMonthsAgo(movies))
        .moviesByGender(movieByGender())
        .build();
  }

  private Map<String, Long> moviesSixMonthsAgo(List<Film> movies) {
    LocalDateTime today = LocalDateTime.now(clock);
    LocalDateTime sixMonthsAgo = today.minusMonths(NUMBER_OF_MONTHS).withDayOfMonth(1);

    Map<YearMonth, Long> groupedMovies =
        movies.stream()
            .filter(obj -> obj.createdAt().toLocalDateTime().isAfter(sixMonthsAgo))
            .collect(
                Collectors.groupingBy(
                    obj -> YearMonth.from(obj.createdAt()), Collectors.counting()));

    for (int i = 0; i <= NUMBER_OF_MONTHS; i++) {
      YearMonth monthToCheck = YearMonth.from(today.minusMonths(i).withDayOfMonth(1));
      groupedMovies.putIfAbsent(monthToCheck, 0L);
    }

    Map<String, Long> result =
        groupedMovies.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(
                Collectors.toMap(
                    e -> getMonthNameInPortuguese(e.getKey().getMonthValue(), e.getKey().getYear()),
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new));

    return result;
  }

  private Map<String, Integer> movieByGender() {
    List<Genre> genres = reader.genres();

    Map<String, Integer> result =
        genres.stream()
            .collect(
                Collectors.groupingBy(
                    Genre::name,
                    Collectors.mapping(
                        g -> g.numberOfMovies(), Collectors.summingInt(Integer::intValue))));

    result =
        result.entrySet().stream()
            .filter(entry -> entry.getValue() != 0)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    return result;
  }

  private String getMonthNameInPortuguese(int monthValue, int year) {
    String[] monthNames = new DateFormatSymbols(Locale.forLanguageTag("pt-BR")).getMonths();
    return "%s - %s".formatted(monthNames[monthValue - 1], year);
  }

  private Double averageRate() {
    List<Integer> notes = reader.ratings();

    return Double.valueOf(
        DECIMAL_FORMAT.format(notes.stream().mapToDouble(n -> n).average().orElse(0)));
  }

  private Integer averageRuntime(List<Film> movies) {
    return (int) movies.stream().mapToDouble(m -> m.runtime()).average().orElse(0);
  }
}
