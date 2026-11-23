package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationAddressUseCase;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationsUseCase;
import com.moviemux.user.domain.exception.AccountDeletionRequiresHistoryMigrationException;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    properties.add("ranking.invitation.address.digest-key",
        () -> Base64.getEncoder().encodeToString(new byte[32]));
    properties.add("ranking.invitation.address.encryption-key",
        () -> Base64.getEncoder().encodeToString("A".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
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
  @Autowired ManageRankingInvitationsUseCase invitations;
  @Autowired ManageRankingInvitationAddressUseCase addresses;
  @Autowired EntityManager entities;
  @Autowired PlatformTransactionManager transactionManager;
  UUID owner, other, movie, wishlist;

  @BeforeEach
  void fixture() {
    jdbc.execute("truncate table \"user\", movie, movie_wishlist restart identity cascade");
    owner = UUID.randomUUID();
    other = UUID.randomUUID();
    movie = UUID.randomUUID();
    wishlist = UUID.randomUUID();
    putAccount(owner);
    putAccount(other);
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
  void unprotectedAccountStillUsesLegacyRemovalWithoutDeletingOtherUsersNotes() {
    jdbc.update("delete from movie_note where user_id=?",owner);
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
    unlinkOwnLegacyNote();
    inviteTarget();
    var original = guardedState();
    assertThatThrownBy(() -> failure.removeThenFail(owner))
        .isInstanceOf(Exception.class)
        .hasMessage("synthetic checked failure");
    assertThat(jdbc.queryForObject("select user_id from movie where id=?", UUID.class, movie))
        .isEqualTo(owner);
    assertThat(count("movie_note")).isEqualTo(1);
    assertThat(count("user_wishlist")).isEqualTo(1);
    assertThat(count("movie_wishlist_user_wishlist")).isEqualTo(1);
    for (String table :
        java.util.List.of("\"user\"", "user_preferences", "user_statistics", "user_roles"))
      assertThat(count(table)).isEqualTo(2);
    assertThat(guardedState()).isEqualTo(original);
  }

  @Test void missingAccountRemainsNoOp() {
    inviteTarget();
    var before = guardedState();
    removal.remove(UUID.randomUUID());
    assertThat(guardedState()).isEqualTo(before);
  }

  @Test void otherwisePermittedDeletionRemovesBothInvitationDirectionsAndAddressOnly() {
    unlinkOwnLegacyNote();
    UUID rank = ranking(other), third = putAccount(UUID.randomUUID()), fourth = putAccount(UUID.randomUUID());
    String address = addresses.rotate(actor(owner));
    String thirdAddress = addresses.rotate(actor(third));
    addresses.rotate(actor(other));
    var received = invitations.issue(actor(other), rank, address).inviteId();
    var unrelated = invitations.issue(actor(other), rank, thirdAddress).inviteId();
    // Historical issuer rows can outlast current invitation authority; seed those exact FK directions directly.
    var sent = seedInvite(rank, owner, fourth, "pending");
    var revoked = seedInvite(rank, owner, third, "revoked");
    var consumed = seedInvite(rank, other, owner, "consumed");
    var survivingInvites = rows("ranking_invitation", "invite_id=?", unrelated);
    var survivingAddresses = rows("ranking_invitation_address", "account_id<>?", owner);
    addContextualHistory(rank, other);
    jdbc.update("insert into ranking_membership_grant(ranking_id,user_id,capability) values (?,?,'READ')",
        rank, other);
    var protectedHistory = snapshot("ranking", "ranking_membership", "ranking_membership_grant",
        "ranking_author_identity", "ranking_movie_entry", "ranking_movie_round", "ranking_movie_rating", "movie_note");

    removal.remove(owner);

    assertDeleted(owner);
    assertThat(jdbc.queryForList("select invite_id from ranking_invitation", UUID.class)).containsExactly(unrelated);
    assertThat(rows("ranking_invitation", "invite_id=?", unrelated)).isEqualTo(survivingInvites);
    assertThat(rows("ranking_invitation_address", "true")).isEqualTo(survivingAddresses);
    assertThat(jdbc.queryForList("select invite_id from ranking_invitation", UUID.class))
        .doesNotContain(received, sent, revoked, consumed);
    assertThat(snapshot("ranking", "ranking_membership", "ranking_membership_grant",
        "ranking_author_identity", "ranking_movie_entry", "ranking_movie_round", "ranking_movie_rating", "movie_note"))
        .isEqualTo(protectedHistory);
    assertThat(jdbc.queryForList("select id from \"user\"", UUID.class)).containsExactlyInAnyOrder(other, third, fourth);
    assertThat(jdbc.queryForMap("select user_id,legacy_eligible from movie where id=?", movie))
        .containsEntry("user_id", null).containsEntry("legacy_eligible", true);
  }

  @Test void standaloneAddressDoesNotBlockOtherwisePermittedDeletion() {
    unlinkOwnLegacyNote();
    addresses.rotate(actor(owner));
    assertThat(count("ranking_invitation_address")).isEqualTo(1);
    assertThat(count("ranking_invitation")).isZero();
    removal.remove(owner);
    assertDeleted(owner);
  }

  @ParameterizedTest
  @ValueSource(strings = {"legacy note", "owner", "active member", "inactive member"})
  void protectedNotesOwnershipAndMembershipLeaveInvitationArtifactsUnchanged(String guard) {
    var fixture = inviteTarget();
    if (!guard.equals("legacy note")) unlinkOwnLegacyNote();
    switch (guard) {
      case "owner" -> ranking(owner);
      case "active member" -> addMembership(fixture.rank(), owner, true);
      case "inactive member" -> addMembership(fixture.rank(), owner, false);
      default -> { }
    }
    var before = guardedState();
    assertThatThrownBy(() -> removal.remove(owner))
        .isInstanceOf(AccountDeletionRequiresHistoryMigrationException.class);
    assertThat(guardedState()).isEqualTo(before);
  }

  @Test void lateDeferredForeignKeyFailureRestoresInvitationArtifactsAtCommit() {
    unlinkOwnLegacyNote();
    inviteTarget();
    jdbc.execute("""
        create table removal_commit_restriction(account_id uuid references "user"(id)
          deferrable initially deferred)
        """);
    try {
      jdbc.update("insert into removal_commit_restriction values (?)", owner);
      var before = guardedState();
      assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        removal.remove(owner);
        entities.flush();
        assertDeleted(owner);
      })).hasRootCauseInstanceOf(PSQLException.class).satisfies(error -> {
        var root = error;
        while (root.getCause() != null) root = root.getCause();
        assertThat(((PSQLException) root).getSQLState()).isEqualTo("23503");
      });
      assertThat(guardedState()).isEqualTo(before);
    } finally {
      jdbc.execute("drop table removal_commit_restriction");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"issue", "accept", "rotate"})
  void removalThatWinsAccountLockPreventsLateInvitationActions(String operation) throws Exception {
    unlinkOwnLegacyNote();
    UUID rank = ranking(other);
    String code = addresses.rotate(actor(owner));
    UUID invite = operation.equals("accept") ? invitations.issue(actor(other), rank, code).inviteId() : null;
    Runnable lateAction = switch (operation) {
      case "issue" -> () -> invitations.issue(actor(other), rank, code);
      case "accept" -> () -> invitations.accept(actor(owner), rank, invite);
      case "rotate" -> () -> addresses.rotate(actor(owner));
      default -> throw new IllegalArgumentException(operation);
    };
    var denial = overlap(() -> removal.remove(owner), lateAction);
    assertThat(denial).isInstanceOf(operation.equals("rotate") ?
        RankingAccessDeniedException.class : RankingInvitationNotFoundException.class);
    assertDeleted(owner);
    assertThat(jdbc.queryForList("select user_id from ranking_membership where ranking_id=?", UUID.class, rank))
        .containsExactly(other);
    assertThat(count("ranking_membership_grant")).isZero();
  }

  @Test void addressRotationRechecksAccountEligibilityAfterWaiting() throws Exception {
    addresses.rotate(actor(owner));
    var before=rows("ranking_invitation_address", "account_id=?", owner);
    var denial=overlap(() -> jdbc.update("""
        update user_statistics set consecutive_failed_login_attempts=10 where user_id=?
        """, owner), () -> addresses.rotate(actor(owner)));
    assertThat(denial).isInstanceOf(RankingAccessDeniedException.class);
    assertThat(rows("ranking_invitation_address", "account_id=?", owner)).isEqualTo(before);
  }

  @Test void acceptanceThatWinsAccountLockCreatesProtectedHistoryBeforeDeletionGuards() throws Exception {
    unlinkOwnLegacyNote();
    var fixture = inviteTarget();
    var originalAddress = rows("ranking_invitation_address", "account_id=?", owner);
    var denial = overlap(() -> invitations.accept(actor(owner), fixture.rank(), fixture.invite()),
        () -> removal.remove(owner));
    assertThat(denial).isInstanceOf(AccountDeletionRequiresHistoryMigrationException.class);
    assertThat(jdbc.queryForObject("select count(*) from \"user\" where id=?", Integer.class, owner)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select active from ranking_membership where ranking_id=? and user_id=?",
        Boolean.class, fixture.rank(), owner)).isTrue();
    assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?",
        String.class, fixture.rank(), owner)).containsExactlyInAnyOrder("READ", "RATE_OWN");
    assertThat(jdbc.queryForObject("select consumed_at is not null from ranking_invitation where invite_id=?",
        Boolean.class, fixture.invite())).isTrue();
    assertThat(rows("ranking_invitation_address", "account_id=?", owner)).isEqualTo(originalAddress);
  }

  private Throwable overlap(Runnable first, Runnable second) throws Exception {
    var transactions = new TransactionTemplate(transactionManager);
    var blockedPid = new CompletableFuture<Integer>();
    var background = new AtomicReference<java.util.concurrent.Future<Throwable>>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(status -> {
        int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        first.run();
        entities.flush();
        background.set(executor.submit(() -> catchThrowable(() -> transactions.executeWithoutResult(contender -> {
          jdbc.execute("set local lock_timeout='12s'");
          blockedPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
          second.run();
        }))));
        assertPostgresBlockedOn(blockedPid, holder);
      });
      return background.get().get(15, TimeUnit.SECONDS);
    }
  }

  private void assertPostgresBlockedOn(CompletableFuture<Integer> waiter, int holder) {
    try {
      int pid = waiter.get(5, TimeUnit.SECONDS);
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      while (System.nanoTime() < deadline) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
            Boolean.class, holder, pid))) return;
        Thread.sleep(20);
      }
      throw new AssertionError("Invitation action/deletion did not reach the intended account lock before commit");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException notStarted) {
      throw new AssertionError("Contending PostgreSQL transaction did not start", notStarted);
    }
  }

  private UUID putAccount(UUID id) {
    jdbc.update("insert into \"user\"(id,name,email,password,created_at) values(?,?,?,'hash',now())",
        id, "Synthetic", id + "@example.test");
    jdbc.update("insert into user_preferences(user_id,notify) values(?,false)", id);
    jdbc.update("insert into user_statistics(user_id,consecutive_failed_login_attempts) values(?,0)", id);
    jdbc.update("insert into user_roles(user_id,roles) values(?,1)", id);
    return id;
  }

  private UUID ranking(UUID id) {
    UUID rank = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values"
          + "(?,?,'PRIVATE','INVITATION',now())", rank, id);
      addMembership(rank, id, true);
    });
    return rank;
  }

  private void addMembership(UUID rank, UUID id, boolean active) {
    jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values(?,?,?,now())",
        rank, id, active);
  }

  private record InviteFixture(UUID rank, UUID invite) {}

  private InviteFixture inviteTarget() {
    UUID rank = ranking(other);
    String code = addresses.rotate(actor(owner));
    return new InviteFixture(rank, invitations.issue(actor(other), rank, code).inviteId());
  }

  private UUID seedInvite(UUID rank, UUID sender, UUID recipient, String status) {
    UUID invite = UUID.randomUUID();
    jdbc.update("""
        insert into ranking_invitation(invite_id,ranking_id,invited_by_user_id,invitee_user_id,
          issued_at,expires_at,consumed_at,revoked_at)
        values (?,?,?,?,now(),now()+interval '168 hours',
          case when ?='consumed' then now() end, case when ?='revoked' then now() end)
        """, invite, rank, sender, recipient, status, status);
    return invite;
  }

  private void addContextualHistory(UUID rank, UUID account) {
    UUID author = UUID.randomUUID(), entry = UUID.randomUUID(), round = UUID.randomUUID();
    jdbc.update("""
        insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)
        """, rank, author, account, UUID.randomUUID());
    jdbc.update("""
        insert into ranking_movie_entry(ranking_id,entry_id,movie_id,added_by_author_id,created_at)
        values (?,?,?,?,now())
        """, rank, entry, movie, author);
    jdbc.update("""
        insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed)
        values (?,?,?,1,now(),true)
        """, rank, entry, round);
    jdbc.update("""
        insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,8,now())
        """, rank, round, author);
  }

  private RankingActor actor(UUID id) {
    return new RankingActor(id, true);
  }

  private void unlinkOwnLegacyNote() {
    jdbc.update("delete from movie_note where user_id=?", owner);
  }

  private void assertDeleted(UUID id) {
    assertThat(jdbc.queryForObject("select count(*) from \"user\" where id=?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ranking_invitation_address where account_id=?",
        Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ranking_invitation where invited_by_user_id=? or invitee_user_id=?",
        Integer.class, id, id)).isZero();
  }

  private Map<String, List<String>> guardedState() {
    return snapshot("\"user\"", "movie", "movie_note", "user_roles", "user_preferences", "user_statistics",
        "user_wishlist", "movie_wishlist_user_wishlist", "ranking", "ranking_membership", "ranking_membership_grant",
        "ranking_invitation", "ranking_invitation_address");
  }

  private Map<String, List<String>> snapshot(String... tables) {
    var values = new TreeMap<String, List<String>>();
    for (String table : tables) values.put(table, rows(table, "true"));
    return values;
  }

  private List<String> rows(String table, String restriction, Object... params) {
    return jdbc.queryForList("select row_to_json(t)::text from " + table + " t where " + restriction + " order by 1",
        String.class, params);
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
