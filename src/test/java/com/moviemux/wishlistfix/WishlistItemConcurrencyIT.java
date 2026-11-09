package com.moviemux.wishlistfix;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.wishlist.adapter.repository.postgres.impl.WishlistPostgresRepository;
import com.moviemux.wishlist.domain.command.AddWishlistMovieCommand;
import com.moviemux.wishlist.domain.command.UpdateWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.AddWishlistMovieUseCase;
import com.moviemux.wishlist.domain.usecase.UpdateWishlistUseCase;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real PostgreSQL locks and independent transactions, with bounded observed interleavings. */
@SpringBootTest
@ActiveProfiles("test")
@Import(WishlistItemConcurrencyIT.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WishlistItemConcurrencyIT {
  private static final String SCHEMA =
      "wishlist_items_test_" + UUID.randomUUID().toString().replace("-", "");
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
        "spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '15s'");
  }

  @AfterAll
  static void removeOwnedSchema() throws Exception {
    if (schemaCreated)
      try (var connection = DriverManager.getConnection(url, username, password)) {
        connection.createStatement().execute("drop schema " + SCHEMA + " cascade");
      }
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired AddWishlistMovieUseCase add;
  @Autowired UpdateWishlistUseCase update;
  @Autowired ScheduledWishlists wishlists;
  @Autowired WishlistPostgresRepository persistence;
  @Autowired KronusCatalogRepository catalog;
  private UUID owner;
  private UUID wishlistId;
  private long seed;

  @BeforeEach
  void fixtures() {
    wishlists.schedule = null;
    reset(catalog);
    owner = UUID.randomUUID();
    wishlistId = UUID.randomUUID();
    seed = Math.abs(UUID.randomUUID().getMostSignificantBits() / 1000);
    jdbc.update(
        "insert into \"user\"(id,name,email,password,created_at) values (?,?,?,?,now())",
        owner,
        "Synthetic item owner",
        owner + "@example.test",
        "synthetic-hash");
    jdbc.update(
        "insert into user_wishlist(id,name,shareable,user_id,created_at) values (?,?,?,?,now())",
        wishlistId,
        "Original name",
        false,
        owner);
    for (int i = 0; i < 102; i++) {
      jdbc.update(
          "insert into movie_wishlist(tmdb_id,title,url_image) values (?,?,?)",
          seed + i,
          "Synthetic movie " + i,
          "https://example.invalid/poster");
    }
  }

  private void storedItems(int count) {
    for (int i = 0; i < count; i++) {
      jdbc.update(
          "insert into movie_wishlist_user_wishlist(wishlist_id,movie_id,wishlist_order)"
              + " values (?,?,?)",
          wishlistId,
          seed + i,
          i + 1);
    }
  }

  private Wishlist replacement(int count) {
    List<MoviesWishlists> items = new ArrayList<>();
    for (int i = 0; i < count; i++) items.add(item(seed + i, i + 1));
    return Wishlist.builder()
        .id(wishlistId)
        .name("Replaced name")
        .shareable(true)
        .moviesWishlists(items)
        .build();
  }

  private MoviesWishlists item(long movieId, int order) {
    return MoviesWishlists.builder()
        .id(new MoviesWishlistsKey(wishlistId, movieId))
        .wishlist(Wishlist.builder().id(wishlistId).build())
        .movieWishlist(MovieWishlist.builder().tmdbId(movieId).build())
        .wishlistOrder(order)
        .build();
  }

  @Test
  void writeAndOwnerLocksRequireAnExistingTransaction() {
    assertThatThrownBy(() -> persistence.findByIdForUpdate(wishlistId))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> persistence.lockOwner(owner))
        .isInstanceOf(IllegalTransactionStateException.class);
    verifyNoInteractions(catalog);
  }

  @Test
  void concurrentAddsAtNinetyNineCommitOnlyTheFirstAndLeaveExactlyHundred() throws Exception {
    storedItems(99);
    List<Outcome> outcomes =
        race(
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 99)),
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 100)));
    assertThat(outcomes.getFirst().failure()).isNull();
    assertThat(outcomes.get(1).failure()).isInstanceOf(WishlistNotFoundException.class);
    assertThat(wishlists.schedule.secondReadSize).isEqualTo(100);
    assertStoredCountAndOrder(100);
    assertThat(movieIds()).contains(seed + 99).doesNotContain(seed + 100);
  }

  @Test
  void concurrentAddsAtNinetyEightPreserveBothDistinctMemberships() throws Exception {
    storedItems(98);
    List<Outcome> outcomes =
        race(
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 99)),
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 100)));
    assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
    assertThat(wishlists.schedule.secondReadSize).isEqualTo(99);
    assertStoredCountAndOrder(100);
    assertThat(movieIds()).contains(seed + 99, seed + 100);
  }

  @Test
  void updateToHundredSerializesBeforeCompetingAddAndRejectsTheAdd() throws Exception {
    storedItems(99);
    List<Outcome> outcomes =
        race(
            () -> update.execute(new UpdateWishlistCommand(replacement(100), owner)),
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 100)));
    assertThat(outcomes.getFirst().failure()).isNull();
    assertThat(outcomes.get(1).failure()).isInstanceOf(WishlistNotFoundException.class);
    assertThat(wishlists.schedule.secondReadSize).isEqualTo(100);
    assertStoredCountAndOrder(100);
    assertThat(movieIds()).contains(seed + 99).doesNotContain(seed + 100);
    assertThat(stored().getName()).isEqualTo("Replaced name");
  }

  @Test
  void laterUpdateKeepsReplacementSemanticsAfterSerializedAdd() throws Exception {
    storedItems(99);
    Wishlist request = replacement(100);
    List<MoviesWishlists> requested = new ArrayList<>(request.getMoviesWishlists());
    requested.set(99, item(seed + 100, 100));
    request.setMoviesWishlists(requested);
    List<Outcome> outcomes =
        race(
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 99)),
            () -> update.execute(new UpdateWishlistCommand(request, owner)));
    assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
    assertThat(wishlists.schedule.secondReadSize).isEqualTo(100);
    assertStoredCountAndOrder(100);
    assertThat(movieIds()).contains(seed + 100).doesNotContain(seed + 99);
  }

  @Test
  void rejectedOversizedUpdateRollsBackAndReleasesTheLockForAValidAdd() throws Exception {
    storedItems(99);
    List<Outcome> outcomes =
        race(
            () -> update.execute(new UpdateWishlistCommand(replacement(101), owner)),
            () -> add.execute(new AddWishlistMovieCommand(wishlistId, owner, seed + 101)));
    assertThat(outcomes.getFirst().failure()).isInstanceOf(WishlistNotFoundException.class);
    assertThat(outcomes.get(1).failure()).isNull();
    assertThat(wishlists.schedule.secondReadSize).isEqualTo(99);
    assertStoredCountAndOrder(100);
    assertThat(movieIds()).contains(seed + 101).doesNotContain(seed + 99, seed + 100);
    assertThat(stored().getName()).isEqualTo("Original name");
    assertThat(stored().isShareable()).isFalse();
  }

  @Test
  void forgedNestedMembershipIdentityCannotModifyAnotherOwnersWishlist() throws Exception {
    UUID otherOwner = UUID.randomUUID();
    UUID otherList = UUID.randomUUID();
    jdbc.update(
        "insert into \"user\"(id,name,email,password,created_at) values (?,?,?,?,now())",
        otherOwner,
        "Other synthetic owner",
        otherOwner + "@example.test",
        "synthetic-hash");
    jdbc.update(
        "insert into user_wishlist(id,name,shareable,user_id,created_at) values (?,?,?,?,now())",
        otherList,
        "Untouched list",
        false,
        otherOwner);
    jdbc.update(
        "insert into movie_wishlist_user_wishlist(wishlist_id,movie_id,wishlist_order) values"
            + " (?,?,?)",
        otherList,
        seed + 100,
        1);
    Wishlist request = replacement(1);
    MoviesWishlists forged = request.getMoviesWishlists().getFirst();
    forged.setId(new MoviesWishlistsKey(otherList, seed + 100));
    forged.setWishlist(Wishlist.builder().id(otherList).build());
    request.setMoviesWishlists(List.of(forged));

    update.execute(new UpdateWishlistCommand(request, owner));

    assertThat(movieIds()).containsExactly(seed);
    Wishlist unchanged = persistence.findById(otherList).orElseThrow();
    assertThat(unchanged.getName()).isEqualTo("Untouched list");
    assertThat(unchanged.getUser().id()).isEqualTo(otherOwner);
    assertThat(unchanged.getUpdatedAt()).isNull();
    assertThat(unchanged.getMoviesWishlists())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getId()).isEqualTo(new MoviesWishlistsKey(otherList, seed + 100));
              assertThat(item.getWishlistOrder()).isEqualTo(1);
            });
    verifyNoInteractions(catalog);
  }

  private Wishlist stored() {
    return persistence.findById(wishlistId).orElseThrow();
  }

  private List<Long> movieIds() {
    return stored().getMoviesWishlists().stream()
        .map(item -> item.getMovieWishlist().getTmdbId())
        .toList();
  }

  private void assertStoredCountAndOrder(int count) {
    assertThat(stored().getMoviesWishlists()).hasSize(count);
    assertThat(stored().getMoviesWishlists())
        .extracting(MoviesWishlists::getWishlistOrder)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.rangeClosed(1, count).boxed().toList());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from movie_wishlist_user_wishlist where wishlist_id=?",
                Integer.class,
                wishlistId))
        .isEqualTo(count);
    verifyNoInteractions(catalog);
  }

  private List<Outcome> race(Callable<?> first, Callable<?> second) throws Exception {
    LockSchedule schedule = new LockSchedule(wishlistId);
    wishlists.schedule = schedule;
    var executor = Executors.newFixedThreadPool(2);
    var firstResult = executor.submit(() -> invoke(first));
    java.util.concurrent.Future<Outcome> secondResult = null;
    try {
      await(schedule.firstRead);
      secondResult = executor.submit(() -> invoke(second));
      await(schedule.secondAttempt);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      boolean blocked = false;
      while (System.nanoTime() < deadline) {
        blocked =
            jdbc.queryForObject(
                "select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, schedule.secondPid);
        if (blocked) break;
        Thread.sleep(10);
      }
      assertThat(blocked).as("Second transaction waits on the first PostgreSQL row lock").isTrue();
      assertThat(secondResult.isDone()).isFalse();
      schedule.releaseFirst.countDown();
      return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
    } finally {
      schedule.releaseFirst.countDown();
      firstResult.cancel(true);
      if (secondResult != null) secondResult.cancel(true);
      executor.shutdownNow();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Outcome invoke(Callable<?> operation) {
    try {
      return new Outcome(operation.call(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(10, TimeUnit.SECONDS)).as("Bounded transaction schedule").isTrue();
  }

  record Outcome(Object value, Throwable failure) {}

  static class LockSchedule {
    final UUID wishlistId;
    final AtomicInteger attempts = new AtomicInteger();
    final CountDownLatch firstRead = new CountDownLatch(1);
    final CountDownLatch secondAttempt = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);
    volatile int secondPid;
    volatile int secondReadSize = -1;

    LockSchedule(UUID wishlistId) {
      this.wishlistId = wishlistId;
    }
  }

  static class ScheduledWishlists implements WishlistRepository {
    final WishlistPostgresRepository delegate;
    final JdbcTemplate jdbc;
    volatile LockSchedule schedule;

    ScheduledWishlists(WishlistPostgresRepository delegate, JdbcTemplate jdbc) {
      this.delegate = delegate;
      this.jdbc = jdbc;
    }

    public Optional<Wishlist> findByIdForUpdate(UUID id) {
      LockSchedule current = schedule;
      if (current == null || !current.wishlistId.equals(id)) return delegate.findByIdForUpdate(id);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      int attempt = current.attempts.incrementAndGet();
      if (attempt == 2) {
        current.secondPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        current.secondAttempt.countDown();
      }
      Optional<Wishlist> snapshot = delegate.findByIdForUpdate(id);
      if (attempt == 1) {
        current.firstRead.countDown();
        try {
          await(current.releaseFirst);
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted synthetic transaction schedule", failure);
        }
      } else if (attempt == 2) {
        current.secondReadSize = snapshot.orElseThrow().getMoviesWishlists().size();
      }
      return snapshot;
    }

    public void lockOwner(UUID ownerId) {
      delegate.lockOwner(ownerId);
    }

    public Optional<Wishlist> findById(UUID id) {
      return delegate.findById(id);
    }

    public List<Wishlist> findByUserIdOrderByCreatedAtDesc(UUID ownerId) {
      return delegate.findByUserIdOrderByCreatedAtDesc(ownerId);
    }

    public Wishlist save(Wishlist value) {
      return delegate.save(value);
    }

    public void delete(Wishlist value) {
      delegate.delete(value);
    }
  }

  @TestConfiguration
  static class Configuration {
    @Bean
    @Primary
    KronusCatalogRepository noExternalCatalog() {
      return mock(KronusCatalogRepository.class);
    }

    @Bean
    @Primary
    ScheduledWishlists scheduledWishlists(WishlistPostgresRepository delegate, JdbcTemplate jdbc) {
      return new ScheduledWishlists(delegate, jdbc);
    }
  }
}
