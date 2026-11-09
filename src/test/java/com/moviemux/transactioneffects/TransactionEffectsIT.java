package com.moviemux.transactioneffects;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.application.spring.effects.AfterCommitEffects;
import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;
import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.command.CreateMovieCommand;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.usecase.CreateMovieUseCase;
import com.moviemux.user.domain.command.RegisterUserCommand;
import com.moviemux.user.domain.exception.DuplicatedUserException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.CreateUserUseCase;
import com.moviemux.user.domain.usecase.RequestPasswordResetUseCase;
import com.moviemux.wishlist.domain.command.UpdateWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.UpdateWishlistUseCase;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TransactionEffectsIT.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionEffectsIT {
  private static final String SCHEMA =
      "effects_test_" + UUID.randomUUID().toString().replace("-", "");
  private static final AtomicLong TMDB_IDS = new AtomicLong(900_000);
  private static String url;
  private static String username;
  private static String password;
  private static boolean schemaCreated;

  @DynamicPropertySource
  static void isolateTestSchema(DynamicPropertyRegistry properties) throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
      throw new IllegalStateException("A disposable local PostgreSQL test database is required");
    }
    username = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    try (var connection = DriverManager.getConnection(url, username, password)) {
      connection.createStatement().execute("create schema " + SCHEMA);
      schemaCreated = true;
    }
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
    properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
  }

  @AfterAll
  static void removeOnlyOwnedSchema() throws Exception {
    if (schemaCreated)
      try (var connection = DriverManager.getConnection(url, username, password)) {
        connection.createStatement().execute("drop schema " + SCHEMA + " cascade");
      }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired CreateUserUseCase register;
  @Autowired RequestPasswordResetUseCase reset;
  @Autowired RecordingEmailGateway mail;
  @Autowired ProbeOperations probe;
  @Autowired CreateMovieUseCase movies;
  @Autowired RecordingAnnouncement announcements;
  @Autowired WishlistRepository wishlists;
  @Autowired UpdateWishlistUseCase updateWishlist;

  @MockitoBean(name = "movieSocketRestRespositoryImpl")
  MovieSocketRestRespositoryImpl sockets;

  @BeforeEach
  void resetSyntheticProbeState() {
    mail.calls.clear();
    mail.fail = false;
    announcements.calls.clear();
    reset(sockets);
    jdbc.execute("create table if not exists effect_parent(id integer primary key)");
    jdbc.execute(
        "create table if not exists effect_probe(id integer primary key, parent_id integer"
            + " references effect_parent(id) deferrable initially deferred)");
    jdbc.execute("delete from effect_probe");
    jdbc.execute("delete from effect_parent");
  }

  private String seedExistingAccount() {
    String email = "existing-" + UUID.randomUUID() + "@example.test";
    jdbc.update(
        "insert into \"user\"(id,name,email,password,created_at) values (?,?,?,?,now())",
        UUID.randomUUID(),
        "Synthetic",
        email,
        "synthetic-hash");
    return email;
  }

  private String invite() {
    String code = "synthetic-" + UUID.randomUUID();
    jdbc.update("insert into user_invites(code) values (?)", code);
    return code;
  }

  private User candidate(String email, String name) {
    return User.builder().name(name).email(email).password("SyntheticPassword1!").build();
  }

  @Test
  void checkedDuplicateRollsBackConsumedInviteAndDoesNotSendWelcome() {
    String email = seedExistingAccount();
    String invitation = invite();
    assertThatThrownBy(
            () ->
                register.execute(
                    new RegisterUserCommand(candidate(email, "Duplicate"), invitation)))
        .isInstanceOf(DuplicatedUserException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_invites where code=?", Integer.class, invitation))
        .isEqualTo(1);
    assertThat(mail.calls).isEmpty();
  }

  @Test
  void failedUserWriteRollsBackPreviouslyConsumedInviteAndDoesNotSend() {
    seedExistingAccount();
    String invitation = invite();
    String email = "invalid-" + UUID.randomUUID() + "@example.test";
    assertThatThrownBy(
            () -> register.execute(new RegisterUserCommand(candidate(email, null), invitation)))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_invites where code=?", Integer.class, invitation))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"user\" where email=?", Integer.class, email))
        .isZero();
    assertThat(mail.calls).isEmpty();
  }

  @Test
  void welcomeIsObservedAfterCommitAndFailureDoesNotUndoAccountOrRetry() throws Exception {
    seedExistingAccount();
    String invitation = invite();
    String email = "welcome-" + UUID.randomUUID() + "@example.test";
    mail.fail = true;
    User created =
        register.execute(new RegisterUserCommand(candidate(email, "Created"), invitation));
    assertThat(created.getId()).isNotNull();
    assertThat(mail.calls).hasSize(1);
    assertThat(mail.calls.getFirst().committedAccountVisible()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"user\" where email=?", Integer.class, email))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_invites where code=?", Integer.class, invitation))
        .isZero();
  }

  @Test
  void resetKeyIsCommittedBeforeDeliveryAndSurvivesEmailFailureWithoutRetry() {
    String email = seedExistingAccount();
    mail.fail = true;
    assertThatCode(() -> reset.execute(email)).doesNotThrowAnyException();
    assertThat(mail.calls).hasSize(1);
    assertThat(mail.calls.getFirst().committedKeyVisible()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select redefine_password_key from \"user\" where email=?", String.class, email))
        .hasSize(200);
  }

  @Test
  void commitTimeConstraintFailureRollsBackRowsAndSuppressesEffects() {
    AtomicInteger effects = new AtomicInteger();
    assertThatThrownBy(() -> probe.failAtCommit(effects)).isInstanceOf(RuntimeException.class);
    assertThat(jdbc.queryForObject("select count(*) from effect_probe", Integer.class)).isZero();
    assertThat(effects).hasValue(0);
  }

  @Test
  void checkedFailureRollsBackAllWritesAndEffectsWhileSuccessDeliversOnce() {
    AtomicInteger effects = new AtomicInteger();
    assertThatThrownBy(() -> probe.failChecked(effects))
        .isInstanceOf(SyntheticCheckedFailure.class);
    assertThat(jdbc.queryForObject("select count(*) from effect_parent", Integer.class)).isZero();
    assertThat(effects).hasValue(0);
    probe.succeed(effects);
    assertThat(jdbc.queryForObject("select count(*) from effect_parent", Integer.class))
        .isEqualTo(1);
    assertThat(effects).hasValue(1);
  }

  @Test
  void wishlistRenameConflictRollsBackEarlierMembershipRemoval() {
    String email = seedExistingAccount();
    UUID owner = jdbc.queryForObject("select id from \"user\" where email=?", UUID.class, email);
    UUID originalId = UUID.randomUUID();
    UUID conflictingId = UUID.randomUUID();
    long movieId = TMDB_IDS.incrementAndGet();
    jdbc.update(
        "insert into user_wishlist(id,name,shareable,user_id,created_at) values (?,?,?,?,now())",
        originalId,
        "Original",
        false,
        owner);
    jdbc.update(
        "insert into user_wishlist(id,name,shareable,user_id,created_at) values (?,?,?,?,now())",
        conflictingId,
        "Already used",
        false,
        owner);
    jdbc.update(
        "insert into movie_wishlist(tmdb_id,title,url_image) values (?,?,?)",
        movieId,
        "Synthetic movie",
        "https://example.test/synthetic");
    jdbc.update(
        "insert into movie_wishlist_user_wishlist(movie_id,wishlist_id,wishlist_order) values"
            + " (?,?,?)",
        movieId,
        originalId,
        1);

    var request =
        wishlists.findById(originalId).orElseThrow().toBuilder()
            .name("Already used")
            .moviesWishlists(List.of())
            .build();
    assertThatThrownBy(() -> updateWishlist.execute(new UpdateWishlistCommand(request, owner)))
        .isInstanceOf(WishlistMovieAlreadyExistsException.class);

    assertThat(
            jdbc.queryForObject(
                "select name from user_wishlist where id=?", String.class, originalId))
        .isEqualTo("Original");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from movie_wishlist_user_wishlist where wishlist_id=? and"
                    + " movie_id=?",
                Integer.class,
                originalId,
                movieId))
        .isEqualTo(1);
    assertThat(wishlists.findById(originalId).orElseThrow().getMoviesWishlists())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getMovieWishlist().getTmdbId()).isEqualTo(movieId);
              assertThat(item.getWishlistOrder()).isEqualTo(1);
            });
    assertThat(mail.calls).isEmpty();
    verifyNoInteractions(sockets);
  }

  private CreateMovieCommand movieCommand(String title) {
    String email = seedExistingAccount();
    UUID owner = jdbc.queryForObject("select id from \"user\" where email=?", UUID.class, email);
    Movie movie =
        Movie.builder()
            .tmdbId(TMDB_IDS.incrementAndGet())
            .portugueseTitle(title)
            .englishTitle(title)
            .originalTitle(title)
            .director("Synthetic")
            .description("Synthetic description")
            .runtime(0)
            .genres(List.of())
            .build();
    return new CreateMovieCommand(movie, owner);
  }

  @Test
  void laterRollbackSuppressesSocketButCannotUndoSynchronousDiscord() {
    CreateMovieCommand command = movieCommand("Rolled back movie");
    assertThatThrownBy(() -> probe.createMovieThenFail(command))
        .isInstanceOf(SyntheticCheckedFailure.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from movie where portuguese_title=?",
                Integer.class,
                "Rolled back movie"))
        .isZero();
    assertThat(announcements.calls).hasSize(1);
    verifyNoInteractions(sockets);
  }

  @Test
  void successfulMovieCommitSendsSocketOnceAfterRowIsVisible() throws Exception {
    CreateMovieCommand command = movieCommand("Committed movie");
    AtomicInteger observedCommittedRows = new AtomicInteger();
    doAnswer(
            call -> {
              try (var connection = DriverManager.getConnection(url, username, password);
                  var statement =
                      connection.prepareStatement(
                          "select count(*) from " + SCHEMA + ".movie where portuguese_title=?")) {
                statement.setString(1, "Committed movie");
                try (var rows = statement.executeQuery()) {
                  rows.next();
                  observedCommittedRows.set(rows.getInt(1));
                }
              }
              throw new IllegalStateException("Synthetic socket delivery failure");
            })
        .when(sockets)
        .notifyAllMovies(null);
    Movie created = movies.execute(command);
    assertThat(created.getId()).isNotNull();
    assertThat(observedCommittedRows).hasValue(1);
    verify(sockets, times(1)).notifyAllMovies(null);
    assertThat(announcements.calls).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from movie where id=?", Integer.class, created.getId()))
        .isEqualTo(1);
  }

  static class RecordingAnnouncement implements MovieAnnouncement {
    final List<UUID> calls = new ArrayList<>();

    public void created(UUID id) {
      calls.add(id);
    }

    public void updated(UUID id) {
      calls.add(id);
    }
  }

  record MailCall(boolean committedAccountVisible, boolean committedKeyVisible) {}

  static class RecordingEmailGateway implements EmailGateway {
    final List<MailCall> calls = new ArrayList<>();
    boolean fail;

    @Override
    public Delivery sendEmail(SendMailTemplate request) {
      try (var connection = DriverManager.getConnection(url, username, password);
          var query =
              connection.prepareStatement(
                  "select redefine_password_key from " + SCHEMA + ".\"user\" where email=?")) {
        query.setString(1, request.getTo());
        try (var rows = query.executeQuery()) {
          boolean found = rows.next();
          calls.add(new MailCall(found, found && rows.getString(1) != null));
        }
      } catch (Exception failure) {
        throw new IllegalStateException("Synthetic independent-connection probe failed", failure);
      }
      if (fail) throw new IllegalStateException("Synthetic mail failure");
      return Delivery.SENT;
    }
  }

  static class SyntheticCheckedFailure extends Exception {}

  static class ProbeOperations {
    private final JdbcTemplate jdbc;
    private final AfterCommitEffects effects;
    private final CreateMovieUseCase movies;

    ProbeOperations(JdbcTemplate jdbc, AfterCommitEffects effects, CreateMovieUseCase movies) {
      this.jdbc = jdbc;
      this.effects = effects;
      this.movies = movies;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createMovieThenFail(CreateMovieCommand command) throws Exception {
      movies.execute(command);
      throw new SyntheticCheckedFailure();
    }

    @Transactional(rollbackFor = Exception.class)
    public void failChecked(AtomicInteger calls) throws SyntheticCheckedFailure {
      jdbc.update("insert into effect_parent values (1)");
      effects.submit("synthetic", calls::incrementAndGet);
      throw new SyntheticCheckedFailure();
    }

    @Transactional(rollbackFor = Exception.class)
    public void failAtCommit(AtomicInteger calls) {
      jdbc.update("insert into effect_probe values (1,999)");
      effects.submit("synthetic", calls::incrementAndGet);
    }

    @Transactional(rollbackFor = Exception.class)
    public void succeed(AtomicInteger calls) {
      jdbc.update("insert into effect_parent values (1)");
      effects.submit("synthetic", calls::incrementAndGet);
    }
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    @Primary
    RecordingEmailGateway recordingEmailGateway() {
      return new RecordingEmailGateway();
    }

    @Bean
    @Primary
    RecordingAnnouncement recordingAnnouncement() {
      return new RecordingAnnouncement();
    }

    @Bean
    ProbeOperations probeOperations(
        JdbcTemplate jdbc, AfterCommitEffects effects, CreateMovieUseCase movies) {
      return new ProbeOperations(jdbc, effects, movies);
    }
  }
}
