package com.moviemux.modulecontracts;

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
class AccountHistoryGuardHttpIT {
  private static final String SCHEMA = "account_history_guard_test_" + UUID.randomUUID().toString().replace("-", "");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicInteger OUTBOUND = new AtomicInteger();
  private static String databaseUrl, databaseUser, databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalStub;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JWTUtil jwt;
  @Autowired private com.moviemux.user.domain.repository.AccountRemoval accountRemoval;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired private jakarta.persistence.EntityManager entities;
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

  @Test void bothDeletionRoutesBlockEveryKindOfProtectedHistoryWithoutAnyMutation() throws Exception {
    var admin=user(true);
    for(String kind:List.of("note","active","inactive","owner")) {
      var target=user(false);
      if(kind.equals("note")) noteFixture(target);
      else if(kind.equals("owner")) ranking(target,"PRIVATE");
      else {
        UUID rank=ranking(admin,"PRIVATE");membership(rank,target,"READ");
        if(kind.equals("inactive"))jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,target.id());
      }
      UUID wishlist=UUID.randomUUID();
      jdbc.update("insert into "+table("user_wishlist")+" (id,created_at,name,shareable,user_id) values (?,now(),'Synthetic',false,?)",wishlist,target.id());
      jdbc.update("update "+table("\"user\"")+" set redefine_password_key='synthetic-reset',redefine_password_key_created_at=now() where id=?",target.id());
      var before=allRows();
      for(String method:List.of("DELETE","POST"))assertProtected(response(http.send(deleteRequest(target.id(),admin.token(),method),HttpResponse.BodyHandlers.ofString())));
      assertThat(allRows()).isEqualTo(before);
    }
  }

  @Test void unprotectedAccountKeepsLegacyBehaviorAndNonAdminCannotDelete() throws Exception {
    var admin=user(true);var target=user(false);
    var before=allRows();
    // Existing legacy bug: the catch-all advice maps AuthorizationDeniedException to HTTP 500.
    // Characterize the sanitized response and unchanged data; this does not claim HTTP 403 works.
    var denied=response(http.send(deleteRequest(target.id(),target.token(),"DELETE"),HttpResponse.BodyHandlers.ofString()));
    assertThat(denied.status()).isEqualTo(500);
    assertThat(fields(denied.body())).containsExactlyInAnyOrder("timestamp","status","error","message","path");
    assertThat(denied.body().required("status").asText()).isEqualTo("500");
    assertThat(denied.body().required("error").asText()).isEqualTo("internal server error");
    assertThat(denied.body().required("message").asText()).isEqualTo("contact the administrator");
    assertThat(denied.body().required("path").asText()).isEqualTo("/api/monolith/restricted/user/"+target.id());
    assertThat(allRows()).isEqualTo(before);
    assertThat(response(http.send(deleteRequest(target.id(),admin.token(),"DELETE"),HttpResponse.BodyHandlers.ofString())).status()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select count(*) from "+table("\"user\"")+" where id=?",Integer.class,target.id())).isZero();
    assertThat(response(http.send(deleteRequest(UUID.randomUUID(),admin.token(),"POST"),HttpResponse.BodyHandlers.ofString())).status()).isEqualTo(200);
  }

  @Test void pendingNoteInsertSerializesBeforeGuardAndPreventsDeletion() throws Exception {
    var admin=user(true);var target=user(false);UUID movie=movieFixture(admin);
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try(var insert=connection.prepareStatement("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)")) {
        insert.setObject(1,movie);insert.setObject(2,target.id());insert.executeUpdate();
        var pending=http.sendAsync(deleteRequest(target.id(),admin.token(),"DELETE"),HttpResponse.BodyHandlers.ofString());
        awaitUserLock(connection);connection.commit();
        assertProtected(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)));
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from "+table("\"user\"")+" where id=?",Integer.class,target.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select note from "+table("movie_note")+" where movie_id=? and user_id=?",Integer.class,movie,target.id())).isEqualTo(8);
  }

  @Test void pendingInactiveMembershipInsertSerializesBeforeGuardAndPreventsDeletion() throws Exception {
    var admin=user(true);var target=user(false);UUID rank=ranking(admin,"PRIVATE");
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try(var insert=connection.prepareStatement("insert into "+table("ranking_membership")+" (ranking_id,user_id,active,created_at) values (?,?,false,now())")) {
        insert.setObject(1,rank);insert.setObject(2,target.id());insert.executeUpdate();
        var pending=http.sendAsync(deleteRequest(target.id(),admin.token(),"POST"),HttpResponse.BodyHandlers.ofString());
        awaitUserLock(connection);connection.commit();
        assertProtected(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)));
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from "+table("\"user\"")+" where id=?",Integer.class,target.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select active from "+table("ranking_membership")+" where ranking_id=? and user_id=?",Boolean.class,rank,target.id())).isFalse();
  }

  @Test void removalWinningLockPreventsLateProtectedReferencesInsteadOfDeletingThem() throws Exception {
    var admin=user(true);UUID movie=movieFixture(admin);UUID rank=ranking(admin,"PRIVATE");
    for(String kind:List.of("note","membership")) {
      var target=user(false);
      var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<String>>();
      new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
        accountRemoval.remove(target.id());entities.flush();
        int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
        pending.set(java.util.concurrent.CompletableFuture.supplyAsync(()->{
          try(var connection=dataSource.getConnection();var insert=connection.prepareStatement(kind.equals("note")
              ? "insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)"
              : "insert into "+table("ranking_membership")+" (ranking_id,user_id,active,created_at) values (?,?,false,now())")) {
            insert.setObject(1,kind.equals("note")?movie:rank);insert.setObject(2,target.id());insert.executeUpdate();return "unexpected success";
          } catch(java.sql.SQLException expected) {return expected.getSQLState();}
        }));
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();boolean waiting=false;
        while(System.nanoTime()<deadline) {
          // This observer is in the deletion transaction, so clear cached statistics snapshots.
          jdbc.execute("select pg_stat_clear_snapshot()");
          waiting=Boolean.TRUE.equals(jdbc.queryForObject("""
              select exists(select 1 from pg_stat_activity activity
              where activity.datname=current_database() and activity.wait_event_type='Lock'
                and ?=any(pg_blocking_pids(activity.pid))
                and activity.query like 'insert into %'
                and exists(select 1 from pg_locks held where held.pid=activity.pid and held.relation=cast(? as regclass)))
              """,Boolean.class,blocker,table("\"user\"")));
          if(waiting)break;
          try {Thread.sleep(20);} catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
        }
        assertThat(waiting).as("Late reference insert reaches user FK lock before deletion commits").isTrue();
      });
      assertThat(pending.get().get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("23503");
      assertThat(jdbc.queryForObject("select count(*) from "+table("\"user\"")+" where id=?",Integer.class,target.id())).isZero();
      assertThat(jdbc.queryForObject("select count(*) from "+table(kind.equals("note")?"movie_note":"ranking_membership")+" where user_id=?",Integer.class,target.id())).isZero();
    }
  }

  private void awaitUserLock(java.sql.Connection blocker) throws Exception {
    int pid;
    try(var statement=blocker.createStatement();var result=statement.executeQuery("select pg_backend_pid()")){result.next();pid=result.getInt(1);}
    long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    while(System.nanoTime()<deadline) {
      if(Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from pg_stat_activity activity
          where activity.datname=current_database() and activity.wait_event_type='Lock'
            and ?=any(pg_blocking_pids(activity.pid))
            and activity.query like 'select id from "user" where id=%for update%'
            and exists(select 1 from pg_locks held where held.pid=activity.pid and held.relation=cast(? as regclass)))
          """,Boolean.class,pid,table("\"user\""))))return;
      Thread.sleep(20);
    }
    throw new AssertionError("Deletion did not reach PostgreSQL user lock barrier");
  }
  private UUID movieFixture(Account owner) {
    UUID movie=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)",movie,owner.id());
    return movie;
  }
  private void noteFixture(Account target) {
    UUID movie=movieFixture(target);
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,target.id());
  }
  private List<String> allRows() {
    var result=new ArrayList<String>();
    for(String name:List.of("\"user\"","user_preferences","user_statistics","user_roles","movie","movie_note","user_wishlist","movie_wishlist_user_wishlist","ranking","ranking_membership","ranking_membership_grant"))
      result.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));
    return result;
  }
  private HttpRequest deleteRequest(UUID target,String token,String method) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/restricted/user/"+target))
        .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody())
        .header("Authorization","Bearer "+token).header("Accept","application/json").build();
  }
  private void assertProtected(ApiResponse response) {
    assertThat(response.status()).isEqualTo(409);
    assertThat(fields(response.body())).containsExactly("code");
    assertThat(response.body().required("code").asText()).isEqualTo("account.deletion_requires_history_migration");
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value->assertThat(value).contains("no-store"));
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

  private List<String> authorizationRows() {
    var result = new ArrayList<String>();
    for (String name : List.of("ranking", "ranking_membership", "ranking_membership_grant")) {
      result.addAll(jdbc.queryForList("select row_to_json(t)::text from " + table(name)
          + " t order by row_to_json(t)::text", String.class));
    }
    return result;
  }

  private static String table(String name) { return SCHEMA + "." + name; }

  private HttpRequest httpRequest(String path, String token, Map<String, String> extraHeaders) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/monolith" + path))
        .timeout(Duration.ofSeconds(10)).GET().header("Accept", "application/json");
    if (token != null) request.header("Authorization", "Bearer " + token);
    extraHeaders.forEach(request::header);
    return request.build();
  }

  private ApiResponse request(String path, String token, Map<String, String> headers) throws Exception {
    return response(http.send(httpRequest(path, token, headers), HttpResponse.BodyHandlers.ofString()));
  }

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
