package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.application.spring.bridge.UserActivityBridge;
import com.moviemux.movie.domain.repository.MovieActivityReader;
import com.moviemux.movie.domain.repository.MovieStatisticsReader;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest
class ModuleQueriesIT {
  private static final String SCHEMA =
      "module_test_" + UUID.randomUUID().toString().replace("-", "");
  private static String url, username, password;

  @DynamicPropertySource
  static void isolatedSchema(DynamicPropertyRegistry properties) throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?"))
      throw new IllegalStateException("A disposable local PostgreSQL test database is required");
    username = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    try (var connection = DriverManager.getConnection(url, username, password)) {
      connection.createStatement().execute("create schema " + SCHEMA);
    }
    properties.add("spring.datasource.url", () -> url);
    properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
  }

  @AfterAll
  static void dropOnlyOwnedSchema() throws Exception {
    if (url != null)
      try (var connection = DriverManager.getConnection(url, username, password)) {
        connection.createStatement().execute("drop schema " + SCHEMA + " cascade");
      }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired MovieStatisticsReader statistics;
  @Autowired MovieActivityReader activity;
  @Autowired UserActivityBridge profile;

  @BeforeEach
  void clearSyntheticFixture() {
    jdbc.execute("truncate table \"user\", movie, movie_genre restart identity cascade");
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into \"user\"(id,name,email,password,created_at) values(?,?,?,?,?)",
        id,
        "Synthetic",
        id + "@example.test",
        "synthetic-hash",
        OffsetDateTime.now());
    jdbc.update(
        "insert into user_statistics(user_id,consecutive_failed_login_attempts) values(?,0)", id);
    return id;
  }

  private UUID movie(UUID owner, int runtime) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into"
            + " movie(id,created_at,description,director,english_title,portuguese_title,runtime,user_id,show_notes)"
            + " values(?,?,?,?,?,?,?,?,false)",
        id,
        OffsetDateTime.parse("2026-10-01T00:00:00Z"),
        "Synthetic",
        "Synthetic",
        "Synthetic",
        "Sintético",
        runtime,
        owner);
    return id;
  }

  @Test
  void statisticsProjectionIsCompleteAfterAdapterTransactionsClose() {
    UUID user = user();
    UUID movie = movie(user, 91);
    jdbc.update(
        "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,?,8)",
        movie,
        user,
        OffsetDateTime.now());
    Long genre =
        jdbc.queryForObject(
            "insert into movie_genre(name,tmdb_id) values('Drama',42) returning id", Long.class);
    jdbc.update("insert into movies_genres(movie_id,genre_id) values(?,?)", movie, genre);
    var films = statistics.movies();
    var ratings = statistics.ratings();
    var genres = statistics.genres();
    assertThat(films)
        .singleElement()
        .satisfies(
            film -> {
              assertThat(film.runtime()).isEqualTo(91);
              assertThat(film.createdAt().toInstant())
                  .isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z").toInstant());
            });
    assertThat(ratings).containsExactly(8);
    assertThat(genres)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.name()).isEqualTo("Drama");
              assertThat(value.numberOfMovies()).isEqualTo(1);
            });
  }

  @Test
  void activityQueriesRespectActorAndExposeOnlyDetachedValues() {
    UUID actor = user();
    UUID other = user();
    UUID registered = movie(actor, 91);
    UUID watched = movie(other, 92);
    jdbc.update(
        "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,?,8)",
        registered,
        actor,
        OffsetDateTime.now());
    jdbc.update(
        "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,?,9)",
        watched,
        actor,
        OffsetDateTime.now());
    jdbc.update(
        "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,?,2)",
        watched,
        other,
        OffsetDateTime.now());
    assertThat(activity.read(actor)).isEqualTo(new MovieActivityReader.Activity(2, 1, 183, 8.5));
    var response = profile.read(actor);
    assertThat(response.ratingsGiven()).isEqualTo(2);
    assertThat(response.registeredMovies()).isEqualTo(1);
    assertThat(response.displayTime()).isEqualTo(183);
    assertThat(response.averageRatingMovies()).isEqualTo(8.5);
  }
}
