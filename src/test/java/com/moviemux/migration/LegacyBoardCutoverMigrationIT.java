package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyBoardCutoverMigrationIT {
  private String url,user,password,schema,runtime;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach void disposableDatabase() throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    String suffix=UUID.randomUUID().toString().replace("-","");
    try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
      s.execute("create schema cutover_test_"+suffix);schema="cutover_test_"+suffix;
      s.execute("create role cutover_runtime_"+suffix+" login");runtime="cutover_runtime_"+suffix;
      s.execute("grant usage on schema "+schema+" to "+runtime);
    }
    source=new DriverManagerDataSource(url,user,password){
      @Override public Connection getConnection() throws SQLException {
        var c=super.getConnection();c.setSchema(schema);return c;
      }
    };
    jdbc=new JdbcTemplate(source);
  }

  @AfterEach void disposeOnlyOwnedResources() throws Exception {
    if(schema!=null)try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
      s.execute("drop schema "+schema+" cascade");
      if(runtime!=null)s.execute("drop role "+runtime);
    }
  }

  @Test void v16UpgradeHasOneDefaultStateWithoutChangingSourcesAndGuardedAtomicActivation() throws Exception {
    assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(16);
    UUID owner=account(),member=account(),destination=UUID.randomUUID(),legacy=movie(owner,true,false);
    movie(owner,false,null);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,'2026-01-02T00:00Z',11)",legacy,owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,'2026-01-03T00:00Z',null)",legacy,member);
    var sourceRows=legacyRows();
    var previousHistory=jdbc.queryForList("select version||':'||checksum from flyway_schema_history order by installed_rank",String.class);
    assertThat(flyway("17").migrate().migrationsExecuted).isEqualTo(1);
    assertThat(flyway("17").migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForList("select version||':'||checksum from flyway_schema_history where version<>'17' order by installed_rank",String.class))
        .isEqualTo(previousHistory);
    assertThat(phase()).isEqualTo("LEGACY");
    assertThat(legacyRows()).isEqualTo(sourceRows);
    var operator=operator();
    assertThatThrownBy(()->operator.preview(owner,destination)).hasMessage("migration.cutover_fence_required");
    assertThatThrownBy(()->operator.fence(owner,destination,false)).hasMessage("migration.maintenance_required");
    assertThatThrownBy(()->new OfflineLegacyRankingMigration(source,100,Set.of(user)).fence(owner,destination,true))
        .hasMessage("migration.runtime_role_unsafe");
    assertThatThrownBy(()->operator.fence(UUID.randomUUID(),destination,true)).hasMessage("migration.owner_not_found");
    assertThat(phase()).isEqualTo("LEGACY");
    verifyRoleIsolated("LEGACY");

    operator.fence(owner,destination,true);
    operator.fence(owner,destination,true); // Same bound setup replay is a no-op.
    assertThat(phase()).isEqualTo("FENCED");
    assertThat(targetCount(destination)).isZero();
    assertThatThrownBy(()->operator.preview(owner,UUID.randomUUID())).hasMessage("migration.cutover_binding_mismatch");
    expectSql("55000",()->jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())",destination,owner));
    expectSql("55000",()->jdbc.update("update movie set show_notes=true where id=?",legacy));
    expectSql("55000",()->jdbc.update("update movie set english_title='Blocked canonical edit during fence' where id=?",legacy));
    expectSql("55000",()->jdbc.update("update movie_note set note=10 where movie_id=?",legacy));
    expectSql("55000",()->jdbc.update("delete from movie_discord where movie_id=?",legacy));
    expectSql("55000",this::account);
    // An independent canonical-only record, invisible to the legacy source, does not expand the plan.
    UUID newCanonical=movie(owner,false,null);
    var plan=operator.preview(owner,destination);
    assertThat(plan.users()).isEqualTo(2);assertThat(plan.movies()).isEqualTo(1);assertThat(plan.ratings()).isEqualTo(2);
    expectSql("23514",()->jdbc.update("update legacy_board_cutover set phase='ACTIVE',activated_at=now() where singleton"));
    assertThat(phase()).isEqualTo("FENCED");
    var stale=new OfflineLegacyRankingMigration.Plan(owner,destination,"e".repeat(64),2,1,2);
    assertThatThrownBy(()->operator.apply(stale,true)).hasMessage("migration.source_changed");

    jdbc.execute("""
        create function cutover_fail_synthetic_vote() returns trigger language plpgsql
        as $$ begin raise exception 'synthetic data roll back'; end; $$
        """);
    jdbc.execute("create trigger cutover_fail_synthetic_vote before insert on ranking_movie_rating for each row execute function cutover_fail_synthetic_vote()");
    try {
      assertThatThrownBy(()->operator.apply(plan,true)).isInstanceOf(DataAccessException.class);
      assertThat(phase()).isEqualTo("FENCED");
      assertThat(targetCount(destination)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from legacy_ranking_migration",Long.class)).isZero();
      assertThat(jdbc.queryForObject("select count(*) from legacy_ranking_movie_map",Long.class)).isZero();
    } finally {
      jdbc.execute("drop trigger cutover_fail_synthetic_vote on ranking_movie_rating");
      jdbc.execute("drop function cutover_fail_synthetic_vote()");
    }

    assertThat(operator.apply(plan,true)).isEqualTo(new OfflineLegacyRankingMigration.Result(false,2,1,2));
    assertThat(phase()).isEqualTo("ACTIVE");
    verifyRoleIsolated("ACTIVE");
    assertThat(jdbc.queryForMap("select owner_user_id,visibility,admission from ranking where id=?",destination))
        .containsEntry("owner_user_id",owner).containsEntry("visibility","PRIVATE").containsEntry("admission","INVITATION");
    assertThat(jdbc.queryForList("select user_id from ranking_membership where ranking_id=?",UUID.class,destination))
        .containsExactlyInAnyOrder(owner,member);
    assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?",String.class,destination,member))
        .containsExactlyInAnyOrder("READ","RATE_OWN","ADD_ENTRY","REMOVE_OWN_ENTRY");
    assertThat(jdbc.queryForList("select movie_id from ranking_movie_entry where ranking_id=?",UUID.class,destination))
        .containsExactly(legacy).doesNotContain(newCanonical);
    assertThat(jdbc.queryForList("select score from ranking_movie_rating where ranking_id=?",Integer.class,destination))
        .containsExactlyInAnyOrder(11,null);
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=?",Boolean.class,destination)).isFalse();
    assertThat(legacyRows().stream().filter(row->!row.contains(newCanonical.toString())).toList()).isEqualTo(sourceRows);
    assertThat(operator.preview(owner,destination)).isEqualTo(plan);
    assertThat(operator.apply(plan,true)).isEqualTo(new OfflineLegacyRankingMigration.Result(true,2,1,2));
    expectSql("23514",()->jdbc.update("update legacy_board_cutover set phase='FENCED',activated_at=null where singleton"));
    expectSql("55000",()->jdbc.update("update movie set show_notes=true where id=?",legacy));
    expectSql("55000",()->jdbc.update("delete from movie_note where movie_id=?",legacy));
    assertThat(jdbc.update("update movie set english_title='Reviewed canonical edit' where id=?",legacy)).isEqualTo(1);
    UUID later=account();
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership where ranking_id=? and user_id=?",Long.class,destination,later)).isZero();
    assertThatThrownBy(()->operator.apply(plan,true)).hasMessage("migration.source_changed");
  }

  @Test void fenceDrainsAlreadyAdmittedLegacyMutationBeforeFinalSnapshot() throws Exception {
    flyway("17").migrate();
    UUID owner=account(),legacy=movie(owner,true,false),destination=UUID.randomUUID();
    var operator=operator();
    try(var writer=source.getConnection();var sql=writer.createStatement()){
      writer.setAutoCommit(false);
      int backend;
      try(var result=sql.executeQuery("select pg_backend_pid()")){result.next();backend=result.getInt(1);}
      try(var update=writer.prepareStatement("update movie set show_notes=true where id=?")){
        update.setObject(1,legacy);assertThat(update.executeUpdate()).isEqualTo(1);
      }
      var pending=CompletableFuture.runAsync(()->operator.fence(owner,destination,true));
      awaitWaiter(backend);
      assertThat(phase()).isEqualTo("LEGACY");
      assertThat(pending).isNotCompleted();
      writer.commit();
      pending.get(10,TimeUnit.SECONDS);
    }
    assertThat(phase()).isEqualTo("FENCED");
    var plan=operator.preview(owner,destination);
    assertThat(operator.apply(plan,true).alreadyApplied()).isFalse();
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=?",Boolean.class,destination)).isTrue();
  }

  private void verifyRoleIsolated(String expected) throws Exception {
    try(var c=source.getConnection();var s=c.createStatement()){
      s.execute("set role "+runtime);
      try(var rows=s.executeQuery("select lock_legacy_board_phase(),current_legacy_board_phase()")){
        assertThat(rows.next()).isTrue();assertThat(List.of(rows.getString(1),rows.getString(2))).containsOnly(expected);
      }
      assertThatThrownBy(()->s.executeUpdate("update legacy_board_cutover set phase='LEGACY' where singleton"))
          .isInstanceOfSatisfying(PSQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  private void awaitWaiter(int blocker) throws Exception {
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
    while(System.nanoTime()<until){
      if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",
          Boolean.class,blocker)))return;
      Thread.sleep(20);
    }
    throw new AssertionError("Fence did not wait on the admitted legacy writer");
  }

  private void expectSql(String state,Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class,
        e->assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class,
            sql->assertThat(sql.getSQLState()).isEqualTo(state)));
  }

  private OfflineLegacyRankingMigration operator(){ return new OfflineLegacyRankingMigration(source,100,Set.of(runtime)); }
  private String phase(){return jdbc.queryForObject("select current_legacy_board_phase()",String.class);}
  private long targetCount(UUID id){return jdbc.queryForObject("select count(*) from ranking where id=?",Long.class,id);}
  private List<String> legacyRows(){
    return jdbc.queryForList("""
        select row_to_json(m)::text from movie m
        union all select row_to_json(n)::text from movie_note n order by 1
        """,String.class);
  }
  private UUID account(){
    UUID id=UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",id,id+"@example.invalid");
    return id;
  }
  private UUID movie(UUID actor,boolean eligible,Boolean revealed){
    UUID id=UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes,legacy_eligible)
        values (?,'2026-01-01T00:00Z','Synthetic','Synthetic','Synthetic','Synthetic',?,?,?)
        """,id,actor,revealed,eligible);
    return id;
  }
  private Flyway flyway(String target){
    return Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).target(target).load();
  }
}
