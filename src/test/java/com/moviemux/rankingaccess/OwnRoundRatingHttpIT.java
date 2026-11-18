package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real HTTP filters, current principal, Flyway and PostgreSQL; no mocked authorization. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnRoundRatingHttpIT {
  private static final String SCHEMA = "own_round_rating_test_" + UUID.randomUUID().toString().replace("-", "");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicInteger OUTBOUND = new AtomicInteger();
  private static String databaseUrl, databaseUser, databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalStub;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JWTUtil jwt;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void isolatedDatabaseAndOutboundStub(DynamicPropertyRegistry properties) throws Exception {
    databaseUrl = System.getenv("TEST_DATABASE_URL");
    if (databaseUrl == null || !databaseUrl.matches(
        "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
      throw new IllegalStateException("Ranking HTTP tests require a disposable loopback PostgreSQL test database");
    }
    databaseUser = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    databasePassword = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    try (var connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
        var statement = connection.createStatement()) {
      statement.execute("create schema " + SCHEMA);
      ownsSchema = true;
    }
    properties.add("spring.datasource.url", () -> databaseUrl);
    properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
    properties.add("spring.flyway.default-schema", () -> SCHEMA);
    properties.add("spring.flyway.schemas", () -> SCHEMA);
    properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '10s'");
    externalStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    externalStub.createContext("/", exchange -> {
      OUTBOUND.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });
    externalStub.start();
    String stubUrl = "http://127.0.0.1:" + externalStub.getAddress().getPort();
    for (String name : List.of("kit.url", "mail.url", "socket.io.api.url", "discord.webhook.url"))
      properties.add(name, () -> stubUrl);
    properties.add("scheduling.enabled", () -> false);
    properties.add("send.mail", () -> false);
    properties.add("discord.webhook.execute", () -> false);
    properties.add("discord.webhook.update", () -> false);
  }

  @AfterAll
  static void releaseOwnedResources() throws Exception {
    try {
      if (ownsSchema) {
        try (var connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
            var statement = connection.createStatement()) {
          statement.execute("drop schema " + SCHEMA + " cascade");
        }
      }
    } finally {
      if (externalStub != null) externalStub.stop(0);
    }
  }

  @BeforeEach
  void cleanOnlyOwnedSchema() {
    jdbc.execute("truncate table " + table("\"user\"") + " restart identity cascade");
    OUTBOUND.set(0);
  }

  @AfterEach
  void accessReadsNeverCallProvidersOrPublishEvents() {
    assertThat(OUTBOUND.get()).isZero();
  }


  @Test void ownCreatePatchAndDuplicateNeverChangeOtherAuthorsGlobalHistoryOrRoundMetadata() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,actor,"RATE_OWN");
    UUID round=round(rank,owner,null),otherAuthor=author(rank,owner);rating(rank,round,otherAuthor,99);
    var stable=unrelatedRows();var history=ratingRows(otherAuthor);
    assertRating(send("POST",rank,round,actor.token(),"{\"score\":0}"),201,round,0);
    UUID own=jdbc.queryForObject("select author_id from "+table("ranking_author_identity")+" where ranking_id=? and account_id=?",UUID.class,rank,actor.id());
    var created=jdbc.queryForObject("select created_at from "+table("ranking_movie_rating")+" where author_id=?",java.sql.Timestamp.class,own);
    assertError(send("POST",rank,round,actor.token(),"{\"score\":10}"),409,"ranking.rating_exists");
    assertThat(jdbc.queryForObject("select score from "+table("ranking_movie_rating")+" where author_id=?",Integer.class,own)).isZero();
    assertRating(send("PATCH",rank,round,actor.token(),"{\"score\":10}"),200,round,10);
    assertThat(jdbc.queryForObject("select created_at from "+table("ranking_movie_rating")+" where author_id=?",java.sql.Timestamp.class,own)).isEqualTo(created);
    assertThat(jdbc.queryForObject("select updated_at from "+table("ranking_movie_rating")+" where author_id=?",java.sql.Timestamp.class,own)).isNotNull();
    var ownBeforeRetry=ratingRows(own);
    assertRating(send("PATCH",rank,round,actor.token(),"{\"score\":10}"),200,round,10);
    assertThat(ratingRows(own)).containsExactlyElementsOf(ownBeforeRetry);
    assertThat(ratingRows(otherAuthor)).containsExactlyElementsOf(history);assertThat(unrelatedRows()).containsExactlyElementsOf(stable);
  }

  @Test void contextualIdentityAndGrantsNeverCrossRankingsOrGiveGlobalAdminOverride() throws Exception {
    var owner=user(false);var other=user(false);var actor=user(false);var admin=user(true);
    UUID a=ranking(owner,"PRIVATE"),b=ranking(other,"PUBLIC"),roundA=round(a,owner,false),roundB=round(b,other,true);
    membership(a,actor,"RATE_OWN");membership(b,actor,"READ");
    assertRating(send("POST",a,roundA,actor.token(),"{\"score\":4}"),201,roundA,4);
    assertError(send("POST",b,roundB,actor.token(),"{\"score\":4}"),404,"ranking.not_found");
    assertError(send("POST",a,roundB,actor.token(),"{\"score\":4}"),404,"ranking.not_found");
    assertError(send("POST",b,roundA,other.token(),"{\"score\":4}"),404,"ranking.not_found");
    assertError(send("POST",a,roundA,admin.token(),"{\"score\":4}"),404,"ranking.not_found");
    assertRating(send("POST",a,roundA,owner.token(),"{\"score\":7}"),201,roundA,7);
    grant(b,actor,"RATE_OWN");assertRating(send("POST",b,roundB,actor.token(),"{\"score\":9}"),201,roundB,9);
    assertThat(jdbc.queryForList("select author_id from "+table("ranking_author_identity")+" where account_id=?",UUID.class,actor.id())).hasSize(2).doesNotHaveDuplicates();
    assertThat(jdbc.queryForObject("select score from "+table("ranking_movie_rating")+" r join "+table("ranking_author_identity")+" a using(ranking_id,author_id) where r.ranking_id=? and a.account_id=?",Integer.class,a,actor.id())).isEqualTo(4);
  }

  @Test void missingOwnPatchAndNewLateMemberPreserveNullAndOutlierHistory() throws Exception {
    var owner=user(false);var late=user(false);UUID rank=ranking(owner,"PRIVATE");
    UUID nullRound=round(rank,owner,null),revealedRound=round(rank,owner,true),hiddenRound=round(rank,owner,false);
    UUID original=author(rank,owner);rating(rank,nullRound,original,null);rating(rank,revealedRound,original,-13);rating(rank,hiddenRound,original,123);
    var before=ratingRows(original);var metadata=unrelatedRows();membership(rank,late,"RATE_OWN");
    assertError(send("PATCH",rank,revealedRound,late.token(),"{\"score\":3}"),404,"ranking.not_found");
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_author_identity")+" where account_id=?",Integer.class,late.id())).isZero();
    for(UUID round:List.of(nullRound,revealedRound,hiddenRound))assertRating(send("POST",rank,round,late.token(),"{\"score\":3}"),201,round,3);
    assertThat(ratingRows(original)).containsExactlyElementsOf(before);
    assertThat(unrelatedRows()).containsExactlyElementsOf(metadata);
    assertError(send("POST",rank,nullRound,owner.token(),"{\"score\":2}"),409,"ranking.rating_exists");
    assertRating(send("PATCH",rank,nullRound,owner.token(),"{\"score\":2}"),200,nullRound,2);
    assertThat(jdbc.queryForObject("select score from "+table("ranking_movie_rating")+" where round_id=? and author_id=?",Integer.class,revealedRound,original)).isEqualTo(-13);
  }

  @Test void strictJsonRejectsMissingNullOutOfRangeFractionsStringsAndBooleansWithoutMutation() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);
    var before=targetRows();
    for(String method:List.of("POST","PATCH"))for(String body:List.of("{}","null","{\"score\":null}","{\"score\":-1}","{\"score\":11}",
        "{\"score\":1.5}","{\"score\":1.0}","{\"score\":\"5\"}","{\"score\":true}","{\"score\":[]}","{\"score\":{}}","{\"score\":2147483648}","{\"score\":1e0}","{"))
      assertError(send(method,rank,round,owner.token(),body),400,"ranking.invalid_rating");
    assertThat(targetRows()).containsExactlyElementsOf(before);
  }

  @Test void archivedRevokedInactiveAndBlockedActorsCannotWrite() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);membership(rank,actor,"RATE_OWN");
    assertRating(send("POST",rank,round,actor.token(),"{\"score\":6}"),201,round,6);
    jdbc.update("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=?",rank,actor.id());
    assertError(send("PATCH",rank,round,actor.token(),"{\"score\":7}"),404,"ranking.not_found");grant(rank,actor,"RATE_OWN");
    jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,actor.id());
    assertError(send("PATCH",rank,round,actor.token(),"{\"score\":7}"),404,"ranking.not_found");
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);
    assertError(send("POST",rank,round,owner.token(),"{\"score\":7}"),409,"ranking.archived_read_only");
    var stable=targetRows();
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());
    assertThat(send("POST",rank,round,owner.token(),"{\"score\":7}").status()).isIn(401,403);
    assertThat(send("POST",rank,round,null,"{\"score\":7}").status()).isIn(401,403);
    assertThat(targetRows()).containsExactlyElementsOf(stable);
  }

  @Test void committedRevocationWhilePostWaitsOnRankingLockDeniesWithoutAllocatingAuthor() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);membership(rank,actor,"RATE_OWN");
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var pending=http.sendAsync(writeRequest("POST",rank,round,actor.token(),"{\"score\":8}"),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,1);
      try(var revoke=blocker.prepareStatement("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='RATE_OWN'")) {
        revoke.setObject(1,rank);revoke.setObject(2,actor.id());revoke.executeUpdate();
      }
      blocker.commit();assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),404,"ranking.not_found");
    }
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_movie_rating"),Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_author_identity"),Integer.class)).isZero();
  }

  @Test void concurrentCreatesHaveExactlyOneWinnerWithoutOverwritingItsScore() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var first=http.sendAsync(writeRequest("POST",rank,round,owner.token(),"{\"score\":2}"),HttpResponse.BodyHandlers.ofString());
      var second=http.sendAsync(writeRequest("POST",rank,round,owner.token(),"{\"score\":8}"),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,2);blocker.commit();
      var a=response(first.get(15,java.util.concurrent.TimeUnit.SECONDS));var b=response(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(201,409);
      var winner=a.status()==201?a:b;var loser=a.status()==409?a:b;
      assertRating(winner,201,round,a.status()==201?2:8);assertError(loser,409,"ranking.rating_exists");
      assertThat(jdbc.queryForObject("select score from "+table("ranking_movie_rating"),Integer.class)).isEqualTo(winner.body().required("score").asInt());
    }
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_movie_rating"),Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_author_identity"),Integer.class)).isEqualTo(1);
  }

  @Test void ratingSqlFailureRollsBackNewAuthorAndRatingAndNeverExposesDatabaseDetails() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);
    membership(rank,actor,"RATE_OWN");var before=targetRows();var sources=unrelatedRows();
    jdbc.execute("create function "+table("synthetic_rating_failure")+"() returns trigger language plpgsql as $$ begin raise exception 'synthetic private rating SQL failure'; end; $$");
    jdbc.execute("create trigger synthetic_rating_failure before insert on "+table("ranking_movie_rating")+" for each row execute function "+table("synthetic_rating_failure")+"()");
    try {
      var failed=send("POST",rank,round,actor.token(),"{\"score\":5}");
      assertError(failed,500,"ranking.operation_failed");
      assertThat(failed.body().toString()).doesNotContain("synthetic","SQL",SCHEMA,actor.id().toString(),actor.email());
      assertThat(targetRows()).containsExactlyElementsOf(before);assertThat(unrelatedRows()).containsExactlyElementsOf(sources);
      assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_author_identity")+" where account_id=?",Integer.class,actor.id())).isZero();
    } finally {
      jdbc.execute("drop trigger synthetic_rating_failure on "+table("ranking_movie_rating"));
      jdbc.execute("drop function "+table("synthetic_rating_failure")+"()");
    }
    assertRating(send("POST",rank,round,actor.token(),"{\"score\":5}"),201,round,5);
  }

  @Test void forgedBodyIdentityAndScopeCannotRedirectCreateOrPatch() throws Exception {
    var owner=user(false);var other=user(false);var actor=user(false);
    UUID a=ranking(owner,"PRIVATE"),b=ranking(other,"PRIVATE"),roundA=round(a,owner,false),roundB=round(b,other,true);
    membership(a,actor,"RATE_OWN");UUID authorA=author(a,owner),authorB=author(b,other);
    rating(a,roundA,authorA,9);rating(b,roundB,authorB,1);
    var originalA=ratingRows(authorA);var originalB=ratingRows(authorB);var metadata=unrelatedRows();
    String extras=",\"actor_id\":\""+owner.id()+"\",\"user_id\":\""+other.id()
        +"\",\"author_id\":\""+authorA+"\",\"ranking_id\":\""+b+"\",\"round_id\":\""+roundB+"\"}";
    assertRating(send("POST",a,roundA,actor.token(),"{\"score\":4"+extras),201,roundA,4);
    assertRating(send("PATCH",a,roundA,actor.token(),"{\"score\":7"+extras),200,roundA,7);
    assertError(send("POST",b,roundB,actor.token(),"{\"score\":7,"+"\"ranking_id\":\""+a+"\",\"round_id\":\""+roundA+"\"}"),404,"ranking.not_found");
    assertThat(ratingRows(authorA)).containsExactlyElementsOf(originalA);assertThat(ratingRows(authorB)).containsExactlyElementsOf(originalB);
    assertThat(unrelatedRows()).containsExactlyElementsOf(metadata);
    assertThat(jdbc.queryForObject("select r.score from "+table("ranking_movie_rating")+" r join "+table("ranking_author_identity")+" a using(ranking_id,author_id) where r.ranking_id=? and r.round_id=? and a.account_id=?",Integer.class,a,roundA,actor.id())).isEqualTo(7);
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_author_identity")+" where ranking_id=? and account_id=?",Integer.class,b,actor.id())).isZero();
  }

  private UUID round(UUID rank,Account owner,Boolean revealed) {
    UUID movie=UUID.randomUUID(),entry=UUID.randomUUID(),round=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,'2020-01-01T00:00:00Z','Synthetic','Synthetic','Synthetic','Synthetic',?)",movie,owner.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,'2020-01-01T00:00:00Z',-99)",movie,owner.id());
    jdbc.update("insert into "+table("ranking_movie_entry")+" (ranking_id,entry_id,movie_id,created_at) values (?,?,?,'2020-01-01T00:00:00Z')",rank,entry,movie);
    jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,description,created_at,updated_at,revealed) values (?,?,?,1,'Synthetic historical round','2020-01-01T00:00:00Z','2020-01-02T00:00:00Z',?)",rank,entry,round,revealed);return round;
  }
  private UUID author(UUID rank,Account actor) {UUID id=UUID.randomUUID();jdbc.update("insert into "+table("ranking_author_identity")+" (ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,id,actor.id(),UUID.randomUUID());return id;}
  private void rating(UUID rank,UUID round,UUID author,Integer score) {jdbc.update("insert into "+table("ranking_movie_rating")+" (ranking_id,round_id,author_id,score,created_at,updated_at) values (?,?,?,?,'2020-01-01T00:00:00Z','2020-01-02T00:00:00Z')",rank,round,author,score);}
  private List<String> ratingRows(UUID author) {return jdbc.queryForList("select row_to_json(t)::text from "+table("ranking_movie_rating")+" t where author_id=? order by round_id",String.class,author);}
  private List<String> unrelatedRows(){return rows(List.of("movie","movie_note","ranking_movie_entry","ranking_movie_round"));}
  private List<String> targetRows(){return rows(List.of("ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));}
  private List<String> rows(List<String> tables){var result=new ArrayList<String>();for(String name:tables)result.addAll(jdbc.queryForList("select '"+name+":'||row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));return result;}
  private HttpRequest writeRequest(String method,UUID rank,UUID round,String token,String body) {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/rounds/"+round+"/rating/me"))
        .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
    if(token!=null)builder.header("Authorization","Bearer "+token);return builder.build();
  }
  private ApiResponse send(String method,UUID rank,UUID round,String token,String body)throws Exception{return response(http.send(writeRequest(method,rank,round,token,body),HttpResponse.BodyHandlers.ofString()));}
  private void assertRating(ApiResponse result,int status,UUID round,int score){assertThat(result.status()).isEqualTo(status);assertThat(fields(result.body())).containsExactlyInAnyOrder("round_id","score");assertThat(result.body().required("round_id").asText()).isEqualTo(round.toString());assertThat(result.body().required("score").asInt()).isEqualTo(score);assertNoStore(result);}
  private void assertError(ApiResponse result,int status,String code){assertThat(result.status()).isEqualTo(status);assertThat(fields(result.body())).containsExactly("code");assertThat(result.body().required("code").asText()).isEqualTo(code);assertNoStore(result);}
  private void assertNoStore(ApiResponse result){assertThat(result.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));}

  private void lockRanking(java.sql.Connection connection,UUID rank) throws Exception {
    try(var statement=connection.prepareStatement("select id from "+table("ranking")+" where id=? for update")) {
      statement.setObject(1,rank);try(var result=statement.executeQuery()){assertThat(result.next()).isTrue();}
    }
  }
  private void awaitBlockedRequests(java.sql.Connection blocker,int expected) throws Exception {
    int pid;try(var statement=blocker.createStatement();var result=statement.executeQuery("select pg_backend_pid()")){result.next();pid=result.getInt(1);}
    long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    while(System.nanoTime()<deadline) {
      Integer waiting=jdbc.queryForObject("""
          select count(*) from pg_stat_activity a where a.datname=current_database() and a.wait_event_type='Lock'
          and a.query like '%from ranking%for update%'
          and exists(select 1 from pg_locks l where l.pid=a.pid and l.relation=cast(? as regclass))
          and (?=any(pg_blocking_pids(a.pid)) or exists(select 1 from unnest(pg_blocking_pids(a.pid)) b(pid) where ?=any(pg_blocking_pids(b.pid))))
          """,Integer.class,table("ranking"),pid,pid);
      if(waiting!=null && waiting>=expected)return;
      Thread.sleep(20);
    }
    throw new AssertionError("Requests did not reach ranking row lock barrier");
  }
  private Account user(boolean admin) {
    UUID id = UUID.randomUUID();
    String email = "ranking-" + id + "@example.invalid";
    jdbc.update("insert into " + table("\"user\"")
        + " (id,created_at,email,name,password) values (?,now(),?,'Synthetic ranking account','synthetic-hash')", id, email);
    jdbc.update("insert into " + table("user_statistics") + " (user_id,consecutive_failed_login_attempts) values (?,0)", id);
    jdbc.update("insert into " + table("user_preferences") + " (user_id,notify) values (?,false)", id);
    jdbc.update("insert into " + table("user_roles") + " (user_id,roles) values (?,2)", id);
    if (admin) jdbc.update("insert into " + table("user_roles") + " (user_id,roles) values (?,1)", id);
    String token = jwt.generateToken(id, email, "Synthetic ranking account",
        admin ? Set.of("ROLE_USER", "ROLE_ADM") : Set.of("ROLE_USER"));
    return new Account(id, email, token);
  }

  private UUID ranking(Account owner, String visibility) throws Exception {
    UUID id = UUID.randomUUID();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var rank = connection.prepareStatement("insert into " + table("ranking")
              + " (id,owner_user_id,visibility,admission,created_at) values (?,?,?,'INVITATION',now())");
          var member = connection.prepareStatement("insert into " + table("ranking_membership")
              + " (ranking_id,user_id,active,created_at) values (?,?,true,now())")) {
        rank.setObject(1, id);
        rank.setObject(2, owner.id());
        rank.setString(3, visibility);
        rank.executeUpdate();
        member.setObject(1, id);
        member.setObject(2, owner.id());
        member.executeUpdate();
        connection.commit();
      } catch (Exception error) {
        connection.rollback();
        throw error;
      }
    }
    return id;
  }

  private void membership(UUID ranking, Account account, String... capabilities) {
    jdbc.update("insert into " + table("ranking_membership")
        + " (ranking_id,user_id,active,created_at) values (?,?,true,now())", ranking, account.id());
    for (String capability : capabilities) grant(ranking, account, capability);
  }

  private void grant(UUID ranking, Account account, String capability) {
    jdbc.update("insert into " + table("ranking_membership_grant")
        + " (ranking_id,user_id,capability) values (?,?,?)", ranking, account.id(), capability);
  }

  private static String table(String name) { return SCHEMA + "." + name; }

  private ApiResponse response(HttpResponse<String> response) throws Exception {
    return new ApiResponse(response.statusCode(), response.body().isBlank() ? NullNode.instance : JSON.readTree(response.body()), response.headers());
  }

  private static Set<String> fields(JsonNode node) {
    var fields = new HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private record Account(UUID id, String email, String token) {}
  private record ApiResponse(int status, JsonNode body, HttpHeaders headers) {}
}
