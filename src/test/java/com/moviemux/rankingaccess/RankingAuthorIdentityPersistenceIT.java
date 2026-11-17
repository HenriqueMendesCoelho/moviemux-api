package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.adapter.repository.postgres.RankingAuthorIdentityPostgresAdapter;
import com.moviemux.ranking.adapter.controller.dto.DeletedRankingAuthorView;
import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class RankingAuthorIdentityPersistenceIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private NamedParameterJdbcTemplate named;
  private DataSourceTransactionManager manager;
  private TransactionTemplate transactions;
  private RankingAuthorIdentityPostgresAdapter allocator;

  @BeforeEach void isolatedSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "ranking_author_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("create schema " + schema);
    }
    var source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection(); connection.setSchema(schema); return connection;
      }
    };
    jdbc = new JdbcTemplate(source);
    named = new NamedParameterJdbcTemplate(source);
    manager = new DataSourceTransactionManager(source);
    transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    allocator = new RankingAuthorIdentityPostgresAdapter(named);
    migrate("3");
  }

  @AfterEach void dropOnlyOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("drop schema " + schema + " cascade");
    }
  }

  @Test void migrationIsEmptyAdditiveAndRepeatableWithoutLegacyAssignment() {
    UUID owner = addUser(), rank = addRanking(owner), movie = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)", movie,owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,owner);
    var before = legacyCatalog();
    assertThat(migrate("4")).isEqualTo(1);
    assertThat(legacyCatalog()).containsExactlyElementsOf(before);
    assertThat(count()).isZero();
    assertThat(jdbc.queryForObject("select note from movie_note where movie_id=?",Integer.class,movie)).isEqualTo(8);
    assertThat(jdbc.queryForObject("select owner_user_id from ranking where id=?",UUID.class,rank)).isEqualTo(owner);
    assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name='ranking_author_identity' order by ordinal_position", String.class,schema))
        .containsExactly("ranking_id","author_id","account_id","deleted_public_code");
    assertThat(migrate("4")).isZero();
  }

  @Test void samePairIsStableAcrossCallsAndInactiveMembershipAndOtherRankingIsIndependent() {
    migrate("4");
    UUID account=addUser(), rank=addRanking(account), other=addRanking(account);
    var first=allocate(rank,account);
    var again=allocate(rank,account);
    assertThat(again.authorId()).isEqualTo(first.authorId());
    assertThat(again.deletedPublicCode()).isEqualTo(first.deletedPublicCode());
    var separate=allocate(other,account);
    assertThat(separate.authorId()).isNotEqualTo(first.authorId());
    assertThat(separate.deletedPublicCode()).isNotEqualTo(first.deletedPublicCode());
    UUID inactive=addUser(); addMember(rank,inactive,false);
    assertThat(allocate(rank,inactive).accountId()).isEqualTo(inactive);
    assertThat(count()).isEqualTo(3);
  }

  @Test void concurrentSamePairAllocatesExactlyOneStableIdentity() throws Exception {
    migrate("4"); UUID account=addUser(),rank=addRanking(account);
    var pool=Executors.newFixedThreadPool(8);
    try {
      var ready=new CountDownLatch(8); var go=new CountDownLatch(1);
      var futures=new ArrayList<Future<RankingAuthorIdentity>>();
      for(int i=0;i<8;i++) futures.add(pool.submit(() -> {
        ready.countDown(); if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        return allocate(rank,account);
      }));
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
      var ids=new HashSet<UUID>(); var codes=new HashSet<UUID>();
      for(var future:futures) { var identity=future.get(20,TimeUnit.SECONDS);ids.add(identity.authorId());codes.add(identity.deletedPublicCode()); }
      assertThat(ids).hasSize(1); assertThat(codes).hasSize(1);assertThat(count()).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }

  @Test void competingInsertWaitsForCommitAndReloadsTheCommittedIdentity() throws Exception {
    assertCompetingTransactionOutcome(false);
  }

  @Test void competingInsertWaitsForRollbackAndCreatesItsOwnIdentity() throws Exception {
    assertCompetingTransactionOutcome(true);
  }

  private void assertCompetingTransactionOutcome(boolean rollbackFirst) throws Exception {
    migrate("4");
    UUID account=addUser(),rank=addRanking(account);
    UUID contenderAuthor=UUID.randomUUID(),contenderCode=UUID.randomUUID();
    var generated=new ArrayDeque<>(List.of(contenderAuthor,contenderCode));
    var contender=new RankingAuthorIdentityPostgresAdapter(named,generated::remove);
    var pool=Executors.newSingleThreadExecutor();
    var contenderPid=new CompletableFuture<Integer>();
    var pending=new java.util.concurrent.atomic.AtomicReference<Future<RankingAuthorIdentity>>();
    try {
      var first=transactions.execute(status -> {
        var allocated=allocator.getOrCreate(rank,account);
        int firstPid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
        pending.set(pool.submit(() -> transactions.execute(second -> {
          contenderPid.complete(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
          return contender.getOrCreate(rank,account);
        })));
        try {
          int secondPid=contenderPid.get(10,TimeUnit.SECONDS);
          awaitAuthorInsertLock(firstPid,secondPid);
          assertThat(pending.get().isDone()).as("Contender is blocked before first transaction resolves").isFalse();
        } catch(Exception failure) { throw new RuntimeException(failure); }
        if(rollbackFirst) status.setRollbackOnly();
        return allocated;
      });
      var second=pending.get().get(20,TimeUnit.SECONDS);
      if(rollbackFirst) {
        assertThat(second.authorId()).isEqualTo(contenderAuthor).isNotEqualTo(first.authorId());
        assertThat(second.deletedPublicCode()).isEqualTo(contenderCode).isNotEqualTo(first.deletedPublicCode());
      } else {
        assertThat(second.authorId()).isEqualTo(first.authorId());
        assertThat(second.deletedPublicCode()).isEqualTo(first.deletedPublicCode());
      }
      assertThat(count()).isEqualTo(1);
      assertThat(allocate(rank,account).authorId()).isEqualTo(second.authorId());
    } finally { pool.shutdownNow(); }
  }

  private void awaitAuthorInsertLock(int blocker,int contender) throws InterruptedException {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<deadline) {
      // Observer is inside A's open transaction: refresh PostgreSQL statistics snapshots.
      jdbc.execute("select pg_stat_clear_snapshot()");
      Boolean waiting=jdbc.queryForObject("""
          select exists(select 1 from pg_stat_activity activity
          where activity.pid=? and activity.datname=current_database()
            and activity.wait_event_type='Lock' and ?=any(pg_blocking_pids(activity.pid))
            and activity.query like '%insert into ranking_author_identity%'
            and exists(select 1 from pg_locks held where held.pid=activity.pid
              and held.relation=cast(? as regclass)))
          """,Boolean.class,contender,blocker,schema+".ranking_author_identity");
      if(Boolean.TRUE.equals(waiting)) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Contender did not reach PostgreSQL author identity lock barrier");
  }

  @Test void databaseCollisionsRetryWithoutAbortingTransaction() {
    migrate("4"); UUID account=addUser(),rank=addRanking(account),other=addRanking(account);
    var first=allocate(rank,account);
    UUID freshAuthor=UUID.randomUUID(),freshCode=UUID.randomUUID();
    var queue=new ArrayDeque<>(List.of(first.authorId(),UUID.randomUUID(),UUID.randomUUID(),first.deletedPublicCode(),freshAuthor,freshCode));
    var collision=new RankingAuthorIdentityPostgresAdapter(named,queue::remove);
    var result=transactions.execute(status -> collision.getOrCreate(other,account));
    assertThat(result.authorId()).isEqualTo(freshAuthor);
    assertThat(result.deletedPublicCode()).isEqualTo(freshCode);assertThat(queue).isEmpty();assertThat(count()).isEqualTo(2);
  }

  @Test void accountAndIdentifierSelfCollisionsAreSkippedAndRetryIsBounded() {
    migrate("4"); UUID account=addUser(),rank=addRanking(account),same=UUID.randomUUID();
    UUID author=UUID.randomUUID(),code=UUID.randomUUID();
    var queue=new ArrayDeque<>(List.of(account,UUID.randomUUID(),UUID.randomUUID(),account,same,same,author,code));
    var collision=new RankingAuthorIdentityPostgresAdapter(named,queue::remove);
    var result=transactions.execute(status -> collision.getOrCreate(rank,account));
    assertThat(result.authorId()).isEqualTo(author);assertThat(result.deletedPublicCode()).isEqualTo(code);
    UUID other=addRanking(account); var calls=new AtomicInteger();
    var exhausted=new RankingAuthorIdentityPostgresAdapter(named,() -> {calls.incrementAndGet();return account;});
    assertThatThrownBy(() -> transactions.execute(status -> exhausted.getOrCreate(other,account))).isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(16);assertThat(count()).isEqualTo(1);
  }

  @Test void absentOrWrongRankingMembershipCannotAllocate() {
    migrate("4"); UUID owner=addUser(),rank=addRanking(owner),outsider=addUser();
    assertThatThrownBy(() -> allocate(rank,outsider)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> allocate(UUID.randomUUID(),owner)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> allocate(rank,UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    assertThat(count()).isZero();
  }

  @Test void allocationParticipatesInOuterRollbackAndRejectsAbsentOrRepeatableReadTransaction() {
    migrate("4"); UUID account=addUser(),rank=addRanking(account);
    transactions.executeWithoutResult(status -> { allocator.getOrCreate(rank,account);status.setRollbackOnly(); });
    assertThat(count()).isZero();
    assertThatThrownBy(() -> allocator.getOrCreate(rank,account)).isInstanceOf(IllegalStateException.class);
    var repeatable=new TransactionTemplate(manager);repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(() -> repeatable.execute(status -> allocator.getOrCreate(rank,account)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
    assertThat(count()).isZero();
  }

  @Test void constraintsRejectCrossContextDuplicatesOriginalIdsAndCodeMutation() {
    migrate("4"); UUID account=addUser(),rank=addRanking(account),other=addRanking(account),outsider=addUser();
    var first=allocate(rank,account);
    assertThatThrownBy(() -> insert(other,first.authorId(),account,UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(other,UUID.randomUUID(),account,first.deletedPublicCode())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(rank,UUID.randomUUID(),account,UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(other,UUID.randomUUID(),outsider,UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(other,account,account,UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(other,UUID.randomUUID(),account,account)).isInstanceOf(DataIntegrityViolationException.class);
    UUID same=UUID.randomUUID();
    assertThatThrownBy(() -> insert(other,same,account,same)).isInstanceOf(DataIntegrityViolationException.class);
    for(String column:List.of("author_id","deleted_public_code","ranking_id"))
      assertThatThrownBy(() -> jdbc.update("update ranking_author_identity set "+column+"=? where author_id=?",UUID.randomUUID(),first.authorId()))
          .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count()).isEqualTo(1);
  }

  @Test void fixtureOnlyUnlinkPreservesCodeAndRecreatedEmailGetsDifferentIdentity() {
    migrate("4"); UUID owner=addUser(),rank=addRanking(owner),account=addUser();addMember(rank,account,true);
    String email=jdbc.queryForObject("select email from \"user\" where id=?",String.class,account);
    var old=allocate(rank,account);
    // Fixture models a future separately authorized lifecycle; no production unlink API exists.
    jdbc.update("update ranking_author_identity set account_id=null where author_id=?",old.authorId());
    jdbc.update("delete from ranking_membership where ranking_id=? and user_id=?",rank,account);
    jdbc.update("delete from \"user\" where id=?",account);
    UUID replacement=addUser();jdbc.update("update \"user\" set email=? where id=?",email,replacement);addMember(rank,replacement,true);
    var fresh=allocate(rank,replacement);
    assertThat(fresh.authorId()).isNotEqualTo(old.authorId());assertThat(fresh.deletedPublicCode()).isNotEqualTo(old.deletedPublicCode());
    var detached=jdbc.queryForObject("select ranking_id,author_id,account_id,deleted_public_code from ranking_author_identity where author_id=?",
        (row,index) -> new RankingAuthorIdentity(row.getObject(1,UUID.class),row.getObject(2,UUID.class),row.getObject(3,UUID.class),row.getObject(4,UUID.class)),old.authorId());
    assertThat(DeletedRankingAuthorView.from(detached).code()).isEqualTo(old.deletedPublicCode());
    // Multiple detached contextual authors are retained, not conflated by NULL account IDs.
    insert(rank,UUID.randomUUID(),null,UUID.randomUUID());assertThat(count()).isEqualTo(3);
  }

  private RankingAuthorIdentity allocate(UUID rank,UUID account) { return transactions.execute(status -> allocator.getOrCreate(rank,account)); }
  private int migrate(String target) { return Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
      .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).locations("classpath:db/migration").load().migrate().migrationsExecuted; }
  private int count() { return jdbc.queryForObject("select count(*) from ranking_author_identity",Integer.class); }
  private UUID addUser() {
    UUID id=UUID.randomUUID();jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic-hash')",id,id+"@example.invalid");return id;
  }
  private UUID addRanking(UUID owner) {
    UUID id=UUID.randomUUID();transactions.executeWithoutResult(status -> {
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())",id,owner);addMember(id,owner,true);
    });return id;
  }
  private void addMember(UUID rank,UUID account,boolean active) { jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,?,now())",rank,account,active); }
  private void insert(UUID rank,UUID author,UUID account,UUID code) { jdbc.update("insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,author,account,code); }
  private List<String> legacyCatalog() {
    return jdbc.queryForList("select table_name||':'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema=? and table_name not in ('ranking_author_identity','flyway_schema_history') order by table_name,ordinal_position",String.class,schema);
  }
}
