package com.moviemux.transactioneffects;

import static org.assertj.core.api.Assertions.assertThat;

import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;
import com.moviemux.user.adapter.repository.postgres.InvitePostgresRepository;
import com.moviemux.user.domain.command.RegisterUserCommand;
import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.InviteRepository;
import com.moviemux.user.domain.usecase.CreateUserUseCase;
import com.moviemux.wishlist.adapter.repository.postgres.impl.WishlistPostgresRepository;
import com.moviemux.wishlist.domain.command.CreateWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.CreateUserWishlistUseCase;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only decorators establish exact competing read/write schedules; all persistence, locks,
 * constraints and transaction commits use the real application adapters and PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(BusinessConcurrencyIT.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessConcurrencyIT {
  private static final String SCHEMA =
      "concurrency_test_" + UUID.randomUUID().toString().replace("-", "");
  private static String url;
  private static String username;
  private static String password;
  private static boolean schemaCreated;

  @DynamicPropertySource
  static void isolatedDatabase(DynamicPropertyRegistry properties) throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
      throw new IllegalStateException("A disposable loopback PostgreSQL test database is required");
    }
    username = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    try (var connection = DriverManager.getConnection(url, username, password)) {
      connection.createStatement().execute("create schema " + SCHEMA);
      schemaCreated = true;
    }
    properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    properties.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    properties.add(
        "spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '10s'");
  }

  @AfterAll
  static void removeOwnedSchema() throws Exception {
    if (schemaCreated)
      try (var connection = DriverManager.getConnection(url, username, password)) {
        connection.createStatement().execute("drop schema " + SCHEMA + " cascade");
      }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired CreateUserWishlistUseCase createWishlist;
  @Autowired CreateUserUseCase createUser;
  @Autowired BarrierWishlists wishlists;
  @Autowired BarrierInvites invites;

  @BeforeEach
  void resetSchedules() {
    wishlists.beforeOwnerLock = null;
    invites.readBarrier = null;
    invites.firstCommitted = null;
  }

  private UUID owner() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into \"user\"(id,name,email,password,created_at) values (?,?,?,?,now())",
        id,
        "Synthetic concurrency owner",
        id + "@example.test",
        "synthetic-hash");
    return id;
  }

  @Test
  void sameOwnerAndNameAllowOnlyOneCommittedWishlistWithIntactStoredState() throws Exception {
    UUID owner = owner();
    wishlists.beforeOwnerLock = new CyclicBarrier(2);
    List<Outcome> outcomes =
        race(
            () -> createWishlist.execute(new CreateWishlistCommand("Shared name", owner)),
            () -> createWishlist.execute(new CreateWishlistCommand("Shared name", owner)));
    assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
    assertThat(outcomes.stream().filter(result -> !result.succeeded()).map(Outcome::failure))
        .singleElement()
        .isInstanceOf(WishlistDuplicatedException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_wishlist where user_id=? and name=?",
                Integer.class,
                owner,
                "Shared name"))
        .isEqualTo(1);
    UUID committedId =
        (UUID)
            outcomes.stream()
                .filter(Outcome::succeeded)
                .map(Outcome::value)
                .map(Wishlist.class::cast)
                .map(Wishlist::getId)
                .findFirst()
                .orElseThrow();
    assertThat(
            jdbc.queryForObject(
                "select id from user_wishlist where user_id=? and name=?",
                UUID.class,
                owner,
                "Shared name"))
        .isEqualTo(committedId);
    assertThat(
            jdbc.queryForObject(
                "select shareable from user_wishlist where id=?", Boolean.class, committedId))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from movie_wishlist_user_wishlist where wishlist_id=?",
                Integer.class,
                committedId))
        .isZero();
  }

  @Test
  void concurrentCreatesAtNineWishlistsCommitOnlyOneAndRespectTheTenWishlistLimit()
      throws Exception {
    UUID owner = owner();
    for (int i = 0; i < 9; i++) {
      jdbc.update(
          "insert into user_wishlist(id,name,shareable,user_id,created_at) values (?,?,?,?,now())",
          UUID.randomUUID(),
          "Existing " + i,
          false,
          owner);
    }
    wishlists.beforeOwnerLock = new CyclicBarrier(2);
    List<Outcome> outcomes =
        race(
            () -> createWishlist.execute(new CreateWishlistCommand("Concurrent A", owner)),
            () -> createWishlist.execute(new CreateWishlistCommand("Concurrent B", owner)));
    assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
    assertThat(outcomes.stream().filter(result -> !result.succeeded()).map(Outcome::failure))
        .singleElement()
        .isInstanceOf(WishlistUserReachedLimitException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_wishlist where user_id=?", Integer.class, owner))
        .isEqualTo(10);
    Wishlist committed =
        outcomes.stream()
            .filter(Outcome::succeeded)
            .map(Outcome::value)
            .map(Wishlist.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(
            jdbc.queryForList(
                "select name from user_wishlist where user_id=? and name like 'Concurrent %'",
                String.class, owner))
        .containsExactly(committed.getName());
    assertThat(
            jdbc.queryForObject(
                "select id from user_wishlist where user_id=? and name=?",
                UUID.class,
                owner,
                committed.getName()))
        .isEqualTo(committed.getId());
  }

  @Test
  void staleInviteSnapshotRejectsSecondRegistrationAndRollsBackItsAccount() throws Exception {
    owner(); // Existing user forces invitation-based registration in both calls.
    String code = "concurrent-invite-" + UUID.randomUUID();
    jdbc.update("insert into user_invites(code) values (?)", code);
    invites.readBarrier = new CyclicBarrier(2);
    invites.firstCommitted = new CountDownLatch(1);
    String firstEmail = "first-" + UUID.randomUUID() + "@example.test";
    String secondEmail = "second-" + UUID.randomUUID() + "@example.test";
    List<Outcome> outcomes =
        race(
            () -> {
              try {
                return createUser.execute(new RegisterUserCommand(candidate(firstEmail), code));
              } finally {
                // The proxied operation has either committed or failed before this signal.
                invites.firstCommitted.countDown();
              }
            },
            () -> {
              invites.waitForFirstCommit.set(true);
              try {
                return createUser.execute(new RegisterUserCommand(candidate(secondEmail), code));
              } finally {
                invites.waitForFirstCommit.remove();
              }
            });
    assertThat(outcomes.getFirst().succeeded()).isTrue();
    assertThat(outcomes.get(1).failure())
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"user\" where email=?", Integer.class, firstEmail))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from \"user\" where email=?", Integer.class, secondEmail))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_invites where code=?", Integer.class, code))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_preferences where user_id in (select id from \"user\""
                    + " where email in (?,?))",
                Integer.class,
                firstEmail,
                secondEmail))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_statistics where user_id in (select id from \"user\""
                    + " where email in (?,?))",
                Integer.class,
                firstEmail,
                secondEmail))
        .isEqualTo(1);
  }

  private User candidate(String email) {
    return User.builder()
        .name("Synthetic registration")
        .email(email)
        .password("SyntheticPassword1!")
        .build();
  }

  record Outcome(Object value, Throwable failure) {
    boolean succeeded() {
      return failure == null;
    }
  }

  private List<Outcome> race(Callable<?> first, Callable<?> second) throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    var firstResult = executor.submit(() -> invoke(first));
    var secondResult = executor.submit(() -> invoke(second));
    try {
      return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
    } finally {
      firstResult.cancel(true);
      secondResult.cancel(true);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Outcome invoke(Callable<?> operation) {
    try {
      return new Outcome(operation.call(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
  }

  private static void await(CyclicBarrier barrier) {
    if (barrier == null) return;
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    try {
      barrier.await(10, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new IllegalStateException("Synthetic read barrier failed", failure);
    }
  }

  static class BarrierWishlists implements WishlistRepository {
    final WishlistPostgresRepository delegate;
    volatile CyclicBarrier beforeOwnerLock;

    BarrierWishlists(WishlistPostgresRepository delegate) {
      this.delegate = delegate;
    }

    public Optional<Wishlist> findById(UUID id) {
      return delegate.findById(id);
    }

    public Optional<Wishlist> findByIdForUpdate(UUID id) {
      return delegate.findByIdForUpdate(id);
    }

    public void lockOwner(UUID ownerId) {
      // Both transactions compete for the same owner lock before either reads the count.
      // A barrier after acquiring the lock would deadlock the serialized operations.
      await(beforeOwnerLock);
      delegate.lockOwner(ownerId);
    }

    public List<Wishlist> findByUserIdOrderByCreatedAtDesc(UUID userId) {
      return delegate.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Wishlist save(Wishlist value) {
      return delegate.save(value);
    }

    public void delete(Wishlist value) {
      delegate.delete(value);
    }
  }

  static class BarrierInvites implements InviteRepository {
    final InvitePostgresRepository delegate;
    volatile CyclicBarrier readBarrier;
    volatile CountDownLatch firstCommitted;
    final ThreadLocal<Boolean> waitForFirstCommit = ThreadLocal.withInitial(() -> false);

    BarrierInvites(InvitePostgresRepository delegate) {
      this.delegate = delegate;
    }

    public Invite findByCode(String code) {
      Invite snapshot = delegate.findByCode(code);
      await(readBarrier);
      return snapshot;
    }

    public List<Invite> findAll() {
      return delegate.findAll();
    }

    public Invite save(Invite invite) {
      return delegate.save(invite);
    }

    public void delete(Invite invite) {
      if (waitForFirstCommit.get()) {
        try {
          if (!firstCommitted.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("First registration did not finish");
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Synthetic invitation schedule interrupted", failure);
        }
      }
      delegate.delete(invite);
    }
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    @Primary
    BarrierWishlists barrierWishlists(WishlistPostgresRepository delegate) {
      return new BarrierWishlists(delegate);
    }

    @Bean
    @Primary
    BarrierInvites barrierInvites(InvitePostgresRepository delegate) {
      return new BarrierInvites(delegate);
    }

    @Bean
    @Primary
    EmailGateway noExternalMail() {
      return request -> EmailGateway.Delivery.DISABLED;
    }
  }
}
