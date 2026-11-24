package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.adapter.repository.postgres.CurrentRankingAccessPostgresAdapter;
import com.moviemux.ranking.domain.model.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RankingAccessPersistenceIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;
  private CurrentRankingAccessPostgresAdapter access;

  @BeforeEach void isolatedDatabaseSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "ranking_access_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("create schema " + schema);
    }
    var dataSource = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        return connection;
      }
    };
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    access = new CurrentRankingAccessPostgresAdapter(new NamedParameterJdbcTemplate(dataSource));
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("drop schema " + schema + " cascade");
    }
  }

  @Test void upgradeIsStrictlyAdditiveAndPreservesLegacyRowsAndCatalog() {
    migrate("1");
    var oldTables = tables();
    assertThat(oldTables).hasSize(13);
    var before = catalog(oldTables);
    UUID owner = addUser();
    UUID movie = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)", movie, owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)", movie, owner);
    assertThat(migrate("2")).isEqualTo(1);
    assertThat(tables()).containsExactlyInAnyOrderElementsOf(union(oldTables, Set.of("ranking", "ranking_membership", "ranking_membership_grant")));
    assertThat(catalog(oldTables)).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForObject("select note from movie_note where movie_id=? and user_id=?", Integer.class, movie, owner)).isEqualTo(8);
    assertThat(jdbc.queryForObject("select user_id from movie where id=?", UUID.class, movie)).isEqualTo(owner);
    assertThat(migrate("2")).isZero();
    assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class)).containsExactly("1", "2");
  }

  @Test void explicitlyBaselinedLegacySchemaUpgradesOnceWithoutChangingCatalogOrHistory() throws Exception {
    // Model the production adoption path using only a disposable schema and synthetic data.
    // Execute the immutable real V1 without Flyway so there is no SQL V1 history entry.
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.setSchema(schema);
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/migration/V1__initial_schema.sql"));
    }
    var oldTables = tables();
    assertThat(oldTables).hasSize(13);
    var before = catalog(oldTables);
    UUID owner = addUser();
    UUID movie = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic baseline history','synthetic','synthetic','synthetic',?)", movie, owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),9)", movie, owner);

    var adopted = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).baselineVersion("1")
        .baselineDescription("Synthetic audited V1 schema").target("2").locations("classpath:db/migration").load();
    assertThatThrownBy(adopted::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
        .hasMessageContaining("non-empty");
    assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name='flyway_schema_history'", Integer.class, schema)).isZero();
    assertThat(catalog(oldTables)).containsExactlyElementsOf(before);

    adopted.baseline(); // Explicit approval applies only to this inspected synthetic test fixture.
    assertThat(catalog(oldTables)).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForList("select version||':'||type from flyway_schema_history where success order by installed_rank", String.class))
        .containsExactly("1:BASELINE");
    assertThat(adopted.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(tables()).containsExactlyInAnyOrderElementsOf(union(oldTables,
        Set.of("ranking", "ranking_membership", "ranking_membership_grant")));
    assertThat(catalog(oldTables)).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForObject("select count(*) from movie_note", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select note from movie_note where movie_id=? and user_id=?", Integer.class, movie, owner)).isEqualTo(9);
    assertThat(jdbc.queryForObject("select user_id from movie where id=?", UUID.class, movie)).isEqualTo(owner);
    assertThat(jdbc.queryForObject("select description from movie where id=?", String.class, movie)).isEqualTo("synthetic baseline history");
    assertThat(jdbc.queryForList("select version||':'||type||':'||success::text from flyway_schema_history order by installed_rank", String.class))
        .containsExactly("1:BASELINE:true", "2:SQL:true");
    assertThat(adopted.migrate().migrationsExecuted).isZero();
    assertThat(catalog(oldTables)).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForList("select version||':'||type||':'||success::text from flyway_schema_history order by installed_rank", String.class))
        .containsExactly("1:BASELINE:true", "2:SQL:true");
  }

  @Test void explicitlyBaselinedLegacySchemaUpgradesToLatestWithoutAssigningOrChangingHistory() throws Exception {
    // Synthetic legacy installation: the actual immutable V1, with no Flyway SQL history.
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.setSchema(schema);
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/migration/V1__initial_schema.sql"));
    }
    UUID owner = addUser(), movie = UUID.randomUUID(), wishlist = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes) values (?,now(),'Synthetic baseline history','Synthetic','Synthetic','Synthetic',?,false)", movie, owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),9)", movie, owner);
    jdbc.update("insert into user_roles(user_id,roles) values (?,0)", owner);
    jdbc.update("insert into movie_discord(movie_id,message_id) values (?,'synthetic-message')", movie);
    Long genre = jdbc.queryForObject("insert into movie_genre(name,tmdb_id) values ('Synthetic',123) returning id", Long.class);
    jdbc.update("insert into movies_genres(genre_id,movie_id) values (?,?)", genre, movie);
    jdbc.update("insert into user_invites(code) values ('synthetic-invite')");
    jdbc.update("insert into user_preferences(user_id,notify) values (?,false)", owner);
    jdbc.update("insert into user_statistics(user_id,consecutive_failed_login_attempts) values (?,2)", owner);
    jdbc.update("insert into user_wishlist(id,created_at,name,shareable,user_id) values (?,now(),'Synthetic',false,?)", wishlist, owner);
    jdbc.update("insert into movie_wishlist(tmdb_id,title,title_english,url_image) values (123,'Synthetic','Synthetic','https://example.invalid/image')");
    jdbc.update("insert into movie_wishlist_user_wishlist(movie_id,wishlist_id,wishlist_order) values (123,?,0)", wishlist);
    var oldTables = tables();
    assertThat(oldTables).hasSize(13);
    var beforeCatalog = catalog(oldTables);
    var beforeRows = tableRows(oldTables);
    assertThat(beforeRows.values()).allSatisfy(rows -> assertThat(rows).hasSize(1));

    // Intentionally no target: this proves the real latest startup path, currently V18.
    var adopted = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).baselineVersion("1")
        .baselineDescription("Synthetic audited V1 schema").locations("classpath:db/migration").load();
    assertThatThrownBy(adopted::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class)
        .hasMessageContaining("non-empty");
    assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name='flyway_schema_history'", Integer.class, schema)).isZero();
    assertThat(catalog(oldTables)).containsExactlyElementsOf(beforeCatalog);
    assertThat(tableRows(oldTables)).isEqualTo(beforeRows);
    adopted.baseline(); // Explicit baseline only in this isolated disposable synthetic schema.
    assertThat(adopted.migrate().migrationsExecuted).isEqualTo(17);
    assertThat(adopted.info().current().getVersion().getVersion()).isEqualTo("18");
    var expectedLegacyMovieCatalog = new ArrayList<>(beforeCatalog);
    expectedLegacyMovieCatalog.addAll(List.of("movie:legacy_eligible:boolean:bool::NO:true:NO:",
        "movie:manual_command_id:uuid:uuid::YES::NO:",
        "movie:manual_command_version:smallint:int2::YES::NO:",
        "movie:manual_command_fingerprint:character varying:varchar:64:YES::NO:"));
    for (String oldDefinition : List.of(
        "movie:portuguese_title:character varying:varchar:150:NO::NO:",
        "movie:english_title:character varying:varchar:150:NO::NO:",
        "movie:director:character varying:varchar:150:NO::NO:",
        "movie:description:text:text::NO::NO:")) {
      assertThat(expectedLegacyMovieCatalog.remove(oldDefinition)).isTrue();
      expectedLegacyMovieCatalog.add(oldDefinition.replace(":NO::NO:", ":YES::NO:"));
    }
    var newTables = Set.of("ranking", "ranking_membership", "ranking_membership_grant", "ranking_author_identity",
        "ranking_movie_entry", "ranking_movie_round", "ranking_movie_rating",
        "legacy_ranking_migration", "legacy_ranking_movie_map", "ranking_invitation", "ranking_invitation_address",
        "ranking_rating_copy_receipt");
    assertThat(tables()).containsExactlyInAnyOrderElementsOf(union(union(oldTables, newTables),Set.of("legacy_board_cutover")));
    assertThat(jdbc.queryForList("select phase from legacy_board_cutover",String.class)).containsExactly("LEGACY");
    assertThat(tableRows(newTables).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
    var upgraded = catalog(oldTables);
    String checkPrefix = "movie:movie_legacy_required_metadata_ck:";
    assertThat(upgraded.stream().filter(definition -> definition.startsWith(checkPrefix)).toList())
        .singleElement().satisfies(definition -> assertThat(definition).startsWith(checkPrefix + "CHECK "));
    String manualCheck = "movie:movie_manual_command_metadata_ck:";
    String manualUnique = "movie:movie_manual_command_id_uk:";
    assertThat(upgraded.stream().filter(definition -> definition.startsWith(manualCheck)).toList())
        .singleElement().satisfies(definition -> assertThat(definition).startsWith(manualCheck + "CHECK "));
    assertThat(upgraded.stream().filter(definition -> definition.startsWith(manualUnique)).toList())
        .hasSize(2).anyMatch(definition -> definition.equals(manualUnique + "UNIQUE (manual_command_id)"))
        .anyMatch(definition -> definition.endsWith("USING btree (manual_command_id)"));
    assertThat(upgraded.stream().filter(definition -> !definition.startsWith(checkPrefix)
        && !definition.startsWith(manualCheck) && !definition.startsWith(manualUnique)).toList())
        .containsExactlyInAnyOrderElementsOf(expectedLegacyMovieCatalog);
    assertThat(tableRows(oldTables)).isEqualTo(beforeRows);
    assertThat(jdbc.queryForList("select legacy_eligible from movie", Boolean.class)).containsExactly(true);
    assertThat(jdbc.queryForList("select version||':'||type||':'||success::text from flyway_schema_history order by installed_rank", String.class))
        .containsExactly("1:BASELINE:true", "2:SQL:true", "3:SQL:true", "4:SQL:true", "5:SQL:true", "6:SQL:true", "7:SQL:true", "8:SQL:true", "9:SQL:true", "10:SQL:true", "11:SQL:true", "12:SQL:true", "13:SQL:true", "14:SQL:true", "15:SQL:true", "16:SQL:true", "17:SQL:true", "18:SQL:true");
    assertThat(adopted.migrate().migrationsExecuted).isZero();
    assertThat(catalog(oldTables)).containsExactlyElementsOf(upgraded);
    assertThat(tableRows(oldTables)).isEqualTo(beforeRows);
    assertThat(jdbc.queryForList("select legacy_eligible from movie", Boolean.class)).containsExactly(true);
    assertThat(tableRows(newTables).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
    assertThat(jdbc.queryForList("select version||':'||type||':'||success::text from flyway_schema_history order by installed_rank", String.class))
        .containsExactly("1:BASELINE:true", "2:SQL:true", "3:SQL:true", "4:SQL:true", "5:SQL:true", "6:SQL:true", "7:SQL:true", "8:SQL:true", "9:SQL:true", "10:SQL:true", "11:SQL:true", "12:SQL:true", "13:SQL:true", "14:SQL:true", "15:SQL:true", "16:SQL:true", "17:SQL:true", "18:SQL:true");
  }

  private Map<String, List<String>> tableRows(Set<String> tableNames) {
    var result = new TreeMap<String, List<String>>();
    // Identifiers originate only from this isolated schema's catalog or fixed test table names.
    for (String table : tableNames) {
      // Compare every V1 field, omitting only the explicitly additive V12/V18 movie columns.
      String row = table.equals("movie")
          ? "(to_jsonb(t) - 'legacy_eligible' - 'manual_command_id' - 'manual_command_version' - 'manual_command_fingerprint')::text"
          : "row_to_json(t)::text";
      result.put(table, jdbc.queryForList("select " + row + " from \"" + table + "\" t order by 1", String.class));
    }
    return result;
  }

  @Test void readerUsesExactRankingAndActorWithFreshMembershipAndGrants() {
    migrate(null);
    UUID ownerA=addUser(), ownerB=addUser(), member=addUser(), outsider=addUser();
    UUID a=addRanking(ownerA), b=addRanking(ownerB);
    addMember(a, member, true, "READ", "RATE_OWN");
    addMember(b, member, true, "READ", "ADD_ENTRY");
    assertThat(access.find(a, member).orElseThrow().membership().orElseThrow().capabilities()).containsExactlyInAnyOrder(RankingCapability.READ, RankingCapability.RATE_OWN);
    assertThat(access.find(b, member).orElseThrow().membership().orElseThrow().capabilities()).containsExactlyInAnyOrder(RankingCapability.READ, RankingCapability.ADD_ENTRY);
    assertThat(access.find(a, outsider).orElseThrow().membership()).isEmpty();
    assertThat(access.find(UUID.randomUUID(), member)).isEmpty();
    assertThat(access.find(a, ownerA).orElseThrow().membership().orElseThrow().capabilities()).isEmpty();
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'", a, member);
    assertThat(access.find(a, member).orElseThrow().membership().orElseThrow().capabilities()).containsExactly(RankingCapability.RATE_OWN);
    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?", a, member);
    assertThat(access.find(a, member).orElseThrow().membership().orElseThrow().active()).isFalse();
    assertThat(access.find(b, member).orElseThrow().membership().orElseThrow().active()).isTrue();
    assertThatNullPointerException().isThrownBy(() -> access.find(null, member));
    assertThatNullPointerException().isThrownBy(() -> access.find(a, null));
  }

  @Test void ownerMustHaveActiveMatchingMembershipAtCommit() {
    migrate(null);
    UUID owner = addUser(), rank = UUID.randomUUID();
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> insertRanking(rank, owner)))
        .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
    assertThat(jdbc.queryForObject("select count(*) from ranking", Integer.class)).isZero();
    UUID valid = addRanking(owner);
    assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
        jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?", valid, owner)))
        .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
    assertThat(access.find(valid, owner).orElseThrow().membership().orElseThrow().active()).isTrue();
    assertThatThrownBy(() -> jdbc.update("update ranking set owner_membership_active=false where id=?", valid))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test void scopedConstraintsRejectOrphansDuplicateGrantsAndUnknownCapabilities() {
    migrate(null);
    UUID owner=addUser(), member=addUser(), rank=addRanking(owner), other=addRanking(addUser());
    addMember(rank, member, true, "READ");
    assertThatThrownBy(() -> addMember(rank, member, true)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> grant(rank, member, "READ")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> grant(other, member, "READ")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> grant(rank, member, "GLOBAL_ADMIN")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking set visibility='UNLISTED' where id=?", rank)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking set admission='UNKNOWN' where id=?", rank)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("delete from \"user\" where id=?", owner)).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select count(*) from ranking", Integer.class)).isEqualTo(2);
  }

  @Test void concurrentMembershipInsertionCommitsExactlyOne() throws Exception {
    migrate(null);
    UUID rank=addRanking(addUser()), member=addUser();
    var start = new CountDownLatch(1);
    var attempts = new ArrayList<CompletableFuture<Boolean>>();
    for (int i=0;i<2;i++) attempts.add(CompletableFuture.supplyAsync(() -> {
      try { start.await(10, TimeUnit.SECONDS); addMember(rank, member, true); return true; }
      catch (DataIntegrityViolationException expected) { return false; }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
    }));
    start.countDown();
    assertThat(attempts.get(0).get(15, TimeUnit.SECONDS) ^ attempts.get(1).get(15, TimeUnit.SECONDS)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership where ranking_id=? and user_id=?", Integer.class, rank, member)).isEqualTo(1);
  }

  @Test void conflictingPreexistingTableFailsWithoutPartialAdoptionOrDestruction() {
    migrate("1");
    jdbc.execute("create table ranking(id integer primary key)");
    jdbc.update("insert into ranking values (42)");
    assertThatThrownBy(() -> migrate(null)).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(jdbc.queryForObject("select id from ranking", Integer.class)).isEqualTo(42);
    assertThat(tables()).doesNotContain("ranking_membership", "ranking_membership_grant");
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2' and success", Integer.class)).isZero();
  }

  private int migrate(String target) {
    var config = Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).locations("classpath:db/migration");
    if (target != null) config.target(target);
    return config.load().migrate().migrationsExecuted;
  }
  private UUID addUser() {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic-hash')", id,id+"@example.invalid");
    return id;
  }
  private void insertRanking(UUID id, UUID owner) {
    jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())", id,owner);
  }
  private UUID addRanking(UUID owner) {
    UUID id=UUID.randomUUID();
    transactions.executeWithoutResult(status -> { insertRanking(id,owner); addMember(id,owner,true); });
    return id;
  }
  private void addMember(UUID rank, UUID member, boolean active, String... grants) {
    jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,?,now())",rank,member,active);
    for(String grant:grants) grant(rank,member,grant);
  }
  private void grant(UUID rank, UUID member, String capability) {
    jdbc.update("insert into ranking_membership_grant(ranking_id,user_id,capability) values (?,?,?)",rank,member,capability);
  }
  private Set<String> tables() {
    return new TreeSet<>(jdbc.queryForList("select table_name from information_schema.tables where table_schema=? and table_name<>'flyway_schema_history'", String.class, schema));
  }
  private Set<String> union(Set<String> first,Set<String> second) { var both=new TreeSet<>(first);both.addAll(second);return both; }
  private List<String> catalog(Set<String> tableNames) {
    String names=tableNames.stream().map(name -> "'"+name+"'").collect(java.util.stream.Collectors.joining(","));
    var result=new ArrayList<String>();
    for(String sql:List.of(
        "select table_name||':'||column_name||':'||data_type||':'||udt_name||':'||coalesce(character_maximum_length::text,'')||':'||is_nullable||':'||coalesce(column_default,'')||':'||is_identity||':'||coalesce(identity_generation,'') from information_schema.columns where table_schema=? and table_name in ("+names+") order by table_name,ordinal_position",
        "select c.relname||':'||con.conname||':'||pg_get_constraintdef(con.oid) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname in ("+names+") order by c.relname,con.conname",
        "select tablename||':'||indexname||':'||indexdef from pg_indexes where schemaname=? and tablename in ("+names+") order by tablename,indexname")) result.addAll(jdbc.queryForList(sql,String.class,schema));
    return result;
  }
}
