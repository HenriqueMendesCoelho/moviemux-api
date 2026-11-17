package com.moviemux.rankingcontent;
import static org.assertj.core.api.Assertions.*;
import com.moviemux.rankingcontent.adapter.repository.postgres.RankingMovieRoundListPostgresAdapter;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
class RankingMovieRoundListPersistenceIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;
  private RankingMovieRoundListPostgresAdapter adapter;

  @BeforeEach void isolatedDatabaseSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "round_list_test_" + UUID.randomUUID().toString().replace("-", "");
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
    adapter = new RankingMovieRoundListPostgresAdapter(new NamedParameterJdbcTemplate(dataSource));
    Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration").load().migrate();
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("drop schema " + schema + " cascade");
    }
  }

  @Test void authorizationScopePaginationAndNullableRevealUseOnlyHistoricalMetadata() {
    UUID owner=user(),member=user(),outsider=user(),ranking=UUID.randomUUID(),entry=UUID.randomUUID(),movie=UUID.randomUUID();
    transactions.executeWithoutResult(status->{
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PUBLIC','OPEN',now())",ranking,owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now()),(?,?,true,now())",ranking,owner,ranking,member);
      jdbc.update("insert into ranking_membership_grant(ranking_id,user_id,capability) values (?,?,'READ')",ranking,member);
      jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title) values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic')",movie);
      jdbc.update("insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at) values (?,?,?,now())",ranking,entry,movie);
    });
    assertThat(adapter.find(owner,ranking,entry,null,2)).hasValue(List.of());
    for(int i=1;i<=3;i++) jdbc.update("insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed) values (?,?,?,?,now(),?)",ranking,entry,UUID.randomUUID(),i,i==1?null:i==3);
    var before=jdbc.queryForList("select row_to_json(r)::text from ranking_movie_round r order by round_number",String.class);
    var page=adapter.find(member,ranking,entry,null,2).orElseThrow();
    assertThat(page).extracting(r->r.number()).containsExactly(1L,2L);
    assertThat(page.getFirst().revealed()).isNull();assertThat(page.getLast().revealed()).isFalse();
    assertThat(adapter.find(member,ranking,entry,2L,2).orElseThrow().getFirst().revealed()).isTrue();
    assertThat(adapter.find(member,ranking,entry,Long.MAX_VALUE,2)).hasValue(List.of());
    assertThat(adapter.find(outsider,ranking,entry,null,2)).isEmpty();
    assertThat(adapter.find(owner,UUID.randomUUID(),entry,null,2)).isEmpty();
    assertThat(adapter.find(owner,ranking,UUID.randomUUID(),null,2)).isEmpty();
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=?",ranking,member);
    assertThat(adapter.find(member,ranking,entry,null,2)).isEmpty();
    jdbc.update("insert into ranking_membership_grant(ranking_id,user_id,capability) values (?,?,'READ')",ranking,member);
    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?",ranking,member);
    assertThat(adapter.find(member,ranking,entry,null,2)).isEmpty();
    jdbc.update("update ranking set archived_at=now() where id=?",ranking);
    assertThat(adapter.find(owner,ranking,entry,null,2).orElseThrow()).hasSize(2);
    assertThat(jdbc.queryForList("select row_to_json(r)::text from ranking_movie_round r order by round_number",String.class)).containsExactlyElementsOf(before);
  }
  private UUID user() {
    UUID id=UUID.randomUUID();jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",id,id+"@example.invalid");return id;
  }
}
