package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RemoveOwnEntryCapabilityMigrationIT {
  private String url,user,password,schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;
  @BeforeEach void setup() throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    schema="own_entry_migration_test_"+UUID.randomUUID().toString().replace("-","");
    try(var connection=DriverManager.getConnection(url,user,password)){connection.createStatement().execute("create schema "+schema);}
    var ds=new DriverManagerDataSource(url,user,password){@Override public Connection getConnection() throws SQLException {var c=super.getConnection();c.setSchema(schema);return c;}};
    jdbc=new JdbcTemplate(ds);transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
  }
  @AfterEach void cleanup() throws Exception {
    if(schema!=null)try(var c=DriverManager.getConnection(url,user,password)){c.createStatement().execute("drop schema "+schema+" cascade");}
  }
  @Test void expandsOnlyVerifiedCapabilityCheckWithoutAssigningOrRewritingGrants() {
    migrate("4");
    String constraint=jdbc.queryForObject("""
        select conname from pg_constraint
        where conrelid='ranking_membership_grant'::regclass and contype='c'
          and pg_get_constraintdef(oid) like '%capability%'
        """,String.class);
    assertThat(constraint).isEqualTo("ranking_membership_grant_capability_check");
    UUID owner=UUID.randomUUID(),member=UUID.randomUUID(),rank=UUID.randomUUID();
    for(UUID id:List.of(owner,member))jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','hash')",id,id+"@example.invalid");
    transactions.executeWithoutResult(tx->{
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())",rank,owner);
      for(UUID id:List.of(owner,member))jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",rank,id);
    });
    for(String grant:List.of("READ","RATE_OWN","ADD_ENTRY","REMOVE_ENTRY","INVITE_MEMBER","MANAGE_MEMBERS"))
      jdbc.update("insert into ranking_membership_grant values (?,?,?)",rank,member,grant);
    var before=rows();
    var otherConstraints=otherConstraints();
    assertThatThrownBy(()->jdbc.update("insert into ranking_membership_grant values (?,?,'REMOVE_OWN_ENTRY')",rank,member)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(migrate("5")).isEqualTo(1);
    assertThat(rows()).isEqualTo(before);
    assertThat(otherConstraints()).isEqualTo(otherConstraints);
    jdbc.update("insert into ranking_membership_grant values (?,?,'REMOVE_OWN_ENTRY')",rank,member);
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership_grant where capability='REMOVE_OWN_ENTRY'",Integer.class)).isEqualTo(1);
    assertThatThrownBy(()->jdbc.update("insert into ranking_membership_grant values (?,?,'ADMIN')",rank,member)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(()->jdbc.update("insert into ranking_membership_grant values (?,?,'REMOVE_OWN_ENTRY')",rank,UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(migrate("5")).isZero();
  }
  private List<String> otherConstraints(){return jdbc.queryForList("select conname || ':' || pg_get_constraintdef(oid) from pg_constraint where connamespace=cast(? as regnamespace) and conname<>'ranking_membership_grant_capability_check' order by conname",String.class,schema);}
  private List<String> rows(){var all=new ArrayList<String>();for(String table:List.of("ranking","ranking_membership","ranking_membership_grant"))all.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class));return all;}
  private int migrate(String target){return Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;}
}
