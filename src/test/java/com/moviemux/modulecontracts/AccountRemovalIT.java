package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
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
@org.springframework.context.annotation.Import(AccountRemovalIT.Configuration.class)
@SpringBootTest
class AccountRemovalIT {
  private static final String SCHEMA =
      "removal_test_" + UUID.randomUUID().toString().replace("-", "");
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
  @Autowired com.moviemux.user.domain.repository.AccountRemoval removal;
  @Autowired FailureProbe failure;
  UUID owner, other, movie, wishlist;

  @BeforeEach
  void fixture() {
    jdbc.execute("truncate table \"user\", movie, movie_wishlist restart identity cascade");
    owner = UUID.randomUUID();
    other = UUID.randomUUID();
    movie = UUID.randomUUID();
    wishlist = UUID.randomUUID();
    for (UUID id : java.util.List.of(owner, other)) {
      jdbc.update(
          "insert into \"user\"(id,name,email,password,created_at) values(?,?,?,'hash',now())",
          id,
          "Synthetic",
          id + "@example.test");
      jdbc.update("insert into user_preferences(user_id,notify) values(?,false)", id);
      jdbc.update(
          "insert into user_statistics(user_id,consecutive_failed_login_attempts) values(?,0)", id);
      jdbc.update("insert into user_roles(user_id,roles) values(?,1)", id);
    }
    jdbc.update(
        "insert into"
            + " movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes)"
            + " values(?,now(),'S','S','S','S',?,false)",
        movie,
        owner);
    for (UUID id : java.util.List.of(owner, other))
      jdbc.update(
          "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,now(),8)",
          movie,
          id);
    jdbc.update(
        "insert into user_wishlist(id,created_at,name,shareable,user_id)"
            + " values(?,now(),'Owned',false,?)",
        wishlist,
        owner);
    jdbc.update("insert into movie_wishlist(tmdb_id,url_image) values(42,'synthetic')");
    jdbc.update(
        "insert into movie_wishlist_user_wishlist(movie_id,wishlist_id,wishlist_order)"
            + " values(42,?,1)",
        wishlist);
  }

  @Test
  void committedRemovalDetachesMoviesAndDeletesOnlyOwnedAccountData() {
    removal.remove(owner);
    assertThat(jdbc.queryForObject("select user_id from movie where id=?", UUID.class, movie))
        .isNull();
    assertThat(count("movie")).isEqualTo(1);
    assertThat(count("movie_note")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select user_id from movie_note", UUID.class)).isEqualTo(other);
    assertThat(count("user_wishlist")).isZero();
    assertThat(count("movie_wishlist_user_wishlist")).isZero();
    assertThat(count("movie_wishlist")).isEqualTo(1);
    for (String table :
        java.util.List.of("\"user\"", "user_preferences", "user_statistics", "user_roles"))
      assertThat(count(table)).isEqualTo(1);
  }

  @Test
  void checkedFailureRollsBackAllModuleWrites() {
    assertThatThrownBy(() -> failure.removeThenFail(owner))
        .isInstanceOf(Exception.class)
        .hasMessage("synthetic checked failure");
    assertThat(jdbc.queryForObject("select user_id from movie where id=?", UUID.class, movie))
        .isEqualTo(owner);
    assertThat(count("movie_note")).isEqualTo(2);
    assertThat(count("user_wishlist")).isEqualTo(1);
    assertThat(count("movie_wishlist_user_wishlist")).isEqualTo(1);
    for (String table :
        java.util.List.of("\"user\"", "user_preferences", "user_statistics", "user_roles"))
      assertThat(count(table)).isEqualTo(2);
  }

  @Test
  void allThreeSqlOwnedForeignKeysRejectOrphans() {
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(() -> jdbc.update("update movie set user_id=? where id=?", missing, movie))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("update user_wishlist set user_id=? where id=?", missing, wishlist))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into movie_note(movie_id,user_id,created_at,note) values(?,?,now(),1)",
                    movie,
                    missing))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Configuration {
    @org.springframework.context.annotation.Bean
    FailureProbe failureProbe(com.moviemux.user.domain.repository.AccountRemoval removal) {
      return new FailureProbe(removal);
    }
  }

  static class FailureProbe {
    private final com.moviemux.user.domain.repository.AccountRemoval removal;

    FailureProbe(com.moviemux.user.domain.repository.AccountRemoval removal) {
      this.removal = removal;
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void removeThenFail(UUID id) throws Exception {
      removal.remove(id);
      throw new Exception("synthetic checked failure");
    }
  }
}
