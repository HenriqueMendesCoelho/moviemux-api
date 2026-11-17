package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable PostgreSQL schema; V6 remains empty until an independently approved migration. */
class ContextualMovieRankingMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void isolatedSchemaAtV5() throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    schema="contextual_movie_test_"+UUID.randomUUID().toString().replace("-","");
    try(var connection=DriverManager.getConnection(url,user,password)) {
      connection.createStatement().execute("create schema "+schema);
    }
    var source=new DriverManagerDataSource(url,user,password) {
      @Override public Connection getConnection() throws SQLException {
        var connection=super.getConnection();connection.setSchema(schema);return connection;
      }
    };
    jdbc=new JdbcTemplate(source);
    transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
    migrate("5");
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if(schema!=null) try(var connection=DriverManager.getConnection(url,user,password)) {
      connection.createStatement().execute("drop schema "+schema+" cascade");
    }
  }

  @Test void v5ToV6AddsOnlyEmptyTablesPreservesExistingCatalogAndEveryLegacyRow() {
    UUID account=account(),rank=ranking(account),movie=movie(account);
    author(rank,account);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),-31)",movie,account);
    jdbc.update("update movie set show_notes=null where id=?",movie);
    var oldTables=tables();var oldCatalog=catalog(oldTables);var oldRows=rows(oldTables);
    assertThat(migrate("6")).isEqualTo(1);
    var expected=new TreeSet<>(oldTables);
    expected.addAll(Set.of("ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));
    assertThat(tables()).isEqualTo(expected);
    assertThat(catalog(oldTables)).containsExactlyElementsOf(oldCatalog);
    assertThat(rows(oldTables)).containsExactlyElementsOf(oldRows);
    for(String table:List.of("ranking_movie_entry","ranking_movie_round","ranking_movie_rating"))
      assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).isZero();
    assertThat(migrate("6")).isZero();
    assertThat(rows(oldTables)).containsExactlyElementsOf(oldRows);
    assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank",String.class))
        .containsExactly("1","2","3","4","5","6");
  }

  @Test void entriesDeduplicateMoviePerRankingButShareGlobalCatalogAcrossRankings() {
    migrate("6");UUID user=account(),a=ranking(user),b=ranking(user),movie=movie(user),entry=UUID.randomUUID();
    insertEntry(a,entry,movie,null);insertEntry(b,entry,movie,null);
    assertThatThrownBy(() -> insertEntry(a,UUID.randomUUID(),movie,null)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertEntry(a,entry,movie(user),null)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertEntry(UUID.randomUUID(),UUID.randomUUID(),movie,null)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertEntry(a,UUID.randomUUID(),UUID.randomUUID(),null)).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry where movie_id=?",Integer.class,movie)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from movie where id=?",Integer.class,movie)).isEqualTo(1);
  }

  @Test void compositeForeignKeysRejectCrossRankingAuthorEntryAndRoundReferences() {
    migrate("6");UUID user=account(),a=ranking(user),b=ranking(user),movie=movie(user);
    UUID authorA=author(a,user),authorB=author(b,user),entryA=UUID.randomUUID(),entryB=UUID.randomUUID();
    assertThatThrownBy(() -> insertEntry(a,entryA,movie,authorB)).isInstanceOf(DataIntegrityViolationException.class);
    insertEntry(a,entryA,movie,authorA);insertEntry(b,entryB,movie,authorB);
    UUID roundA=UUID.randomUUID(),roundB=UUID.randomUUID();
    assertThatThrownBy(() -> insertRound(a,entryB,UUID.randomUUID(),1,null)).isInstanceOf(DataIntegrityViolationException.class);
    insertRound(a,entryA,roundA,1,false);insertRound(b,entryB,roundB,1,true);
    assertThatThrownBy(() -> insertRating(a,roundA,authorB,8)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRating(a,roundB,authorA,8)).isInstanceOf(DataIntegrityViolationException.class);
    insertRating(a,roundA,authorA,8);
    assertThatThrownBy(() -> insertRating(a,roundA,authorA,9)).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_rating",Integer.class)).isEqualTo(1);
  }

  @Test void roundNumbersArePositiveUniquePerEntryAndRoundIdentityIsRankingScoped() {
    migrate("6");UUID user=account(),a=ranking(user),b=ranking(user),movie=movie(user);
    UUID entryA=UUID.randomUUID(),entryB=UUID.randomUUID(),secondEntry=UUID.randomUUID(),round=UUID.randomUUID();
    insertEntry(a,entryA,movie,null);insertEntry(b,entryB,movie,null);insertEntry(a,secondEntry,movie(user),null);
    insertRound(a,entryA,round,1,null);insertRound(b,entryB,round,1,null);
    insertRound(a,secondEntry,UUID.randomUUID(),1,null);
    assertThatThrownBy(() -> insertRound(a,entryA,UUID.randomUUID(),1,null)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertRound(a,secondEntry,round,2,null)).isInstanceOf(DataIntegrityViolationException.class);
    for(long invalid:new long[]{0,-1,Long.MIN_VALUE})
      assertThatThrownBy(() -> insertRound(a,entryA,UUID.randomUUID(),invalid,null)).isInstanceOf(DataIntegrityViolationException.class);
    insertRound(a,entryA,UUID.randomUUID(),Long.MAX_VALUE,null);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round",Integer.class)).isEqualTo(4);
  }

  @Test void nullableAndOutOfScaleLegacyValuesArePreservableWithoutInventedNormalization() {
    migrate("6");UUID user=account(),rank=ranking(user),author=author(rank,user),entry=UUID.randomUUID();
    insertEntry(rank,entry,movie(user),null);
    int number=0;
    for(Integer score:Arrays.asList(null,Integer.MIN_VALUE,-1,0,10,11,Integer.MAX_VALUE)) {
      UUID round=UUID.randomUUID();insertRound(rank,entry,round,++number,null);insertRating(rank,round,author,score);
      assertThat(jdbc.queryForObject("select score from ranking_movie_rating where ranking_id=? and round_id=?",Integer.class,rank,round)).isEqualTo(score);
      assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",Boolean.class,rank,round)).isNull();
    }
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_rating where updated_at is null",Integer.class)).isEqualTo(7);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round where description is null and updated_at is null",Integer.class)).isEqualTo(7);
    assertThatThrownBy(() -> jdbc.update("update ranking_movie_rating set created_at=null")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking_movie_round set created_at=null")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking_movie_entry set created_at=null")).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test void deletionAndParentKeyChangesRemainRestrictiveWithoutCascade() {
    migrate("6");UUID user=account(),rank=ranking(user),author=author(rank,user),movie=movie(user),entry=UUID.randomUUID(),round=UUID.randomUUID();
    insertEntry(rank,entry,movie,author);insertRound(rank,entry,round,1,false);insertRating(rank,round,author,6);
    for(String sql:List.of("delete from ranking_movie_round","delete from ranking_movie_entry","delete from ranking_author_identity","delete from movie","delete from ranking"))
      assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking_movie_entry set entry_id=?",UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update ranking_movie_round set round_id=?",UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select score from ranking_movie_rating",Integer.class)).isEqualTo(6);
    assertThat(jdbc.queryForList("select confdeltype::text||confupdtype::text from pg_constraint where connamespace=cast(? as regnamespace) and contype='f' and conrelid in (cast(? as regclass),cast(? as regclass),cast(? as regclass))",String.class,schema,schema+".ranking_movie_entry",schema+".ranking_movie_round",schema+".ranking_movie_rating"))
        .hasSize(6).containsOnly("aa");
  }

  private int migrate(String target) { return Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
      .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).locations("classpath:db/migration").load().migrate().migrationsExecuted; }
  private UUID account() {
    UUID id=UUID.randomUUID();jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic-hash')",id,id+"@example.invalid");return id;
  }
  private UUID ranking(UUID owner) {
    UUID id=UUID.randomUUID();transactions.executeWithoutResult(status -> {
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())",id,owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",id,owner);
    });return id;
  }
  private UUID author(UUID rank,UUID account) {
    UUID id=UUID.randomUUID();jdbc.update("insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,id,account,UUID.randomUUID());return id;
  }
  private UUID movie(UUID account) {
    UUID id=UUID.randomUUID();jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?)",id,account);return id;
  }
  private void insertEntry(UUID rank,UUID entry,UUID movie,UUID author) {
    jdbc.update("insert into ranking_movie_entry(ranking_id,entry_id,movie_id,added_by_author_id,created_at) values (?,?,?,?,now())",rank,entry,movie,author);
  }
  private void insertRound(UUID rank,UUID entry,UUID round,long number,Boolean revealed) {
    jdbc.update("insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed) values (?,?,?,?,now(),?)",rank,entry,round,number,revealed);
  }
  private void insertRating(UUID rank,UUID round,UUID author,Integer score) {
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",rank,round,author,score);
  }
  private Set<String> tables() { return new TreeSet<>(jdbc.queryForList("select table_name from information_schema.tables where table_schema=? and table_name<>'flyway_schema_history'",String.class,schema)); }
  private List<String> rows(Set<String> tables) {
    var result=new ArrayList<String>();
    for(String name:tables) result.addAll(jdbc.queryForList("select '"+name+":'||row_to_json(t)::text from \""+name+"\" t order by row_to_json(t)::text",String.class));
    return result;
  }
  private List<String> catalog(Set<String> tables) {
    String names=tables.stream().map(name -> "'"+name+"'").collect(java.util.stream.Collectors.joining(","));
    var result=new ArrayList<String>();
    for(String query:List.of(
        "select table_name||':'||column_name||':'||data_type||':'||udt_name||':'||coalesce(character_maximum_length::text,'')||':'||is_nullable||':'||coalesce(column_default,'')||':'||is_identity||':'||coalesce(identity_generation,'') from information_schema.columns where table_schema=? and table_name in ("+names+") order by table_name,ordinal_position",
        "select c.relname||':'||con.conname||':'||pg_get_constraintdef(con.oid) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname in ("+names+") order by c.relname,con.conname",
        "select tablename||':'||indexname||':'||indexdef from pg_indexes where schemaname=? and tablename in ("+names+") order by tablename,indexname")) result.addAll(jdbc.queryForList(query,String.class,schema));
    return result;
  }
}
