package com.moviemux.transactioneffects;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.command.CreateMovieCommand;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.usecase.CreateMovieUseCase;
import com.moviemux.user.domain.command.RegisterUserCommand;
import com.moviemux.user.domain.command.RedeemPasswordResetCommand;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.usecase.RedeemPasswordResetUseCase;
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
  @Autowired RedeemPasswordResetUseCase redeem;
  @MockitoBean(name = "mailExecutor") java.util.concurrent.ThreadPoolExecutor mailExecutor;
  private final java.util.ArrayDeque<Runnable> queuedMail = new java.util.ArrayDeque<>();
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
    queuedMail.clear();
    reset(mailExecutor);
    doAnswer(call -> { queuedMail.add(call.getArgument(0)); return null; })
        .when(mailExecutor).execute(any(Runnable.class));
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
    deliverOneQueuedMail();
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
  void rejectedMailQueueDoesNotUndoCommittedAccountAndNeverSendsOrRetries() throws Exception {
    seedExistingAccount();
    String invitation = invite();
    String email = "rejected-" + UUID.randomUUID() + "@example.test";
    doThrow(new java.util.concurrent.RejectedExecutionException("Synthetic saturation"))
        .when(mailExecutor).execute(any(Runnable.class));
    User created = register.execute(new RegisterUserCommand(candidate(email, "Created"), invitation));
    assertThat(created.getId()).isNotNull();
    assertThat(jdbc.queryForObject(
        "select count(*) from \"user\" where email=?", Integer.class, email)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from user_invites where code=?", Integer.class, invitation)).isZero();
    assertThat(mail.calls).isEmpty();
    assertThat(queuedMail).isEmpty();
    verify(mailExecutor, times(1)).execute(any(Runnable.class));
  }

  @Test
  void resetKeyIsCommittedBeforeDeliveryAndSurvivesEmailFailureWithoutRetry() {
    String email = seedExistingAccount();
    mail.fail = true;
    assertThatCode(() -> reset.execute(email)).doesNotThrowAnyException();
    deliverOneQueuedMail();
    assertThat(mail.calls).hasSize(1);
    assertThat(mail.calls.getFirst().committedKeyVisible()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select redefine_password_key from \"user\" where email=?", String.class, email))
        .hasSize(200);
  }

  private void deliverOneQueuedMail() {
    assertThat(mail.calls).isEmpty();
    assertThat(queuedMail).hasSize(1);
    assertThatCode(queuedMail.remove()::run).doesNotThrowAnyException();
    assertThat(queuedMail).isEmpty();
  }

  private RedeemPasswordResetCommand seedResetAccount(int ageHours) {
    String email = seedExistingAccount();
    String key = "synthetic-reset-" + UUID.randomUUID();
    jdbc.update(
        "update \"user\" set redefine_password_key=?, redefine_password_key_created_at="
            + "now() - (? * interval '1 hour') where email=?",
        key, ageHours, email);
    return new RedeemPasswordResetCommand(key, email, "SyntheticChangedPassword1!");
  }

  @Test
  void successfulResetConfirmationObservesCommittedPasswordAndClearedKeyWithoutRetry()
      throws Exception {
    var command = seedResetAccount(0);
    mail.fail = true;
    var changed = redeem.execute(command);
    assertThat(changed.getPassword()).isNotEqualTo("synthetic-hash");
    deliverOneQueuedMail();
    assertThat(mail.calls).singleElement().satisfies(call -> {
      assertThat(call.templateId()).isEqualTo("password_updated");
      assertThat(call.committedAccountVisible()).isTrue();
      assertThat(call.committedKeyVisible()).isFalse();
      assertThat(call.committedPassword()).isEqualTo(changed.getPassword());
      assertThat(call.keyTimestampCleared()).isTrue();
    });
  }

  @Test
  void expiredKeyCleanupCommitsButNeverQueuesPasswordUpdatedMail() {
    var command = seedResetAccount(2);
    assertThatThrownBy(() -> redeem.execute(command))
        .isInstanceOf(UserRedefinePasswordKeyInvalid.class);
    assertThat(jdbc.queryForObject(
        "select redefine_password_key is null and redefine_password_key_created_at is null"
            + " and password='synthetic-hash' from \"user\" where email=?",
        Boolean.class, command.email())).isTrue();
    assertThat(queuedMail).isEmpty();
    assertThat(mail.calls).isEmpty();
  }

  @Test
  void rollbackAfterSuccessfulResetSuppressesConfirmationAndRestoresPasswordAndKey() {
    var command = seedResetAccount(0);
    assertThatThrownBy(() -> probe.redeemThenFail(command))
        .isInstanceOf(SyntheticCheckedFailure.class);
    assertResetUnchanged(command);
  }

  @Test
  void resetCommitFailureSuppressesConfirmationAndRestoresPasswordAndKey() {
    var command = seedResetAccount(0);
    assertThatThrownBy(() -> probe.redeemThenFailAtCommit(command))
        .isInstanceOf(RuntimeException.class);
    assertResetUnchanged(command);
  }

  private void assertResetUnchanged(RedeemPasswordResetCommand command) {
    assertThat(jdbc.queryForObject(
        "select redefine_password_key from \"user\" where email=?", String.class, command.email()))
        .isEqualTo(command.key());
    assertThat(jdbc.queryForObject(
        "select password from \"user\" where email=?", String.class, command.email()))
        .isEqualTo("synthetic-hash");
    assertThat(queuedMail).isEmpty();
    assertThat(mail.calls).isEmpty();
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

  record MailCall(String templateId, boolean committedAccountVisible, boolean committedKeyVisible,
      String committedPassword, boolean keyTimestampCleared) {}

  static class RecordingEmailGateway implements EmailGateway {
    final List<MailCall> calls = new ArrayList<>();
    boolean fail;

    @Override
    public Delivery sendEmail(SendMailTemplate request) {
      try (var connection = DriverManager.getConnection(url, username, password);
          var query =
              connection.prepareStatement(
                  "select redefine_password_key, password, redefine_password_key_created_at from " + SCHEMA + ".\"user\" where email=?")) {
        query.setString(1, request.to());
        try (var rows = query.executeQuery()) {
          boolean found = rows.next();
          calls.add(new MailCall(request.templateId(), found, found && rows.getString(1) != null,
              found ? rows.getString(2) : null, found && rows.getObject(3) == null));
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
    private final RedeemPasswordResetUseCase redeem;

    ProbeOperations(JdbcTemplate jdbc, AfterCommitEffects effects, CreateMovieUseCase movies,
        RedeemPasswordResetUseCase redeem) {
      this.jdbc = jdbc;
      this.effects = effects;
      this.movies = movies;
      this.redeem = redeem;
    }

    @Transactional(rollbackFor = Exception.class)
    public void redeemThenFail(RedeemPasswordResetCommand command) throws Exception {
      redeem.execute(command);
      throw new SyntheticCheckedFailure();
    }

    @Transactional(rollbackFor = Exception.class)
    public void redeemThenFailAtCommit(RedeemPasswordResetCommand command) throws Exception {
      redeem.execute(command);
      jdbc.update("insert into effect_probe values (1,999)");
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
        JdbcTemplate jdbc, AfterCommitEffects effects, CreateMovieUseCase movies,
        RedeemPasswordResetUseCase redeem) {
      return new ProbeOperations(jdbc, effects, movies, redeem);
    }
  }
}
