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
class LeaveRankingHttpIT {
  private static final String SCHEMA = "ranking_leave_http_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test void departureIsIdempotentPreservesGrantsAndRevokesSameTokenAccess() throws Exception {
    var owner = user(false); var member = user(false);
    UUID rank = ranking(owner, "PRIVATE");
    membership(rank, member, "READ", "RATE_OWN");
    UUID movie = UUID.randomUUID();
    jdbc.update("insert into " + table("movie")
        + " (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)", movie, member.id());
    jdbc.update("insert into " + table("movie_note") + " (movie_id,user_id,created_at,note) values (?,?,now(),8)", movie, member.id());
    var legacyNotes = jdbc.queryForList("select row_to_json(t)::text from " + table("movie_note") + " t", String.class);
    var grants = jdbc.queryForList("select row_to_json(t)::text from " + table("ranking_membership_grant") + " t order by capability", String.class);
    assertThat(request("/rankings/" + rank + "/access", member.token(), Map.of()).status()).isEqualTo(200);
    assertLeft(leave(rank, member.token(), Map.of()));
    var after = authorizationRows();
    assertLeft(leave(rank, member.token(), Map.of()));
    assertThat(authorizationRows()).isEqualTo(after);
    assertThat(jdbc.queryForObject("select active from " + table("ranking_membership") + " where ranking_id=? and user_id=?", Boolean.class, rank, member.id())).isFalse();
    assertThat(jdbc.queryForList("select row_to_json(t)::text from " + table("ranking_membership_grant") + " t order by capability", String.class)).isEqualTo(grants);
    assertThat(jdbc.queryForList("select row_to_json(t)::text from " + table("movie_note") + " t", String.class)).isEqualTo(legacyNotes);
    assertThat(request("/rankings/" + rank + "/access", member.token(), Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings/access", member.token(), Map.of()).body().required("items").size()).isZero();
  }

  @Test void noReadNeededButForeignIdentitiesAndAdminCannotLeaveForSomeoneElse() throws Exception {
    var owner = user(false); var member = user(false); var outsider = user(true);
    UUID rank = ranking(owner, "PRIVATE"); membership(rank, member, "RATE_OWN");
    var before = authorizationRows();
    var forged = Map.of("X-User-Id", member.id().toString(), "X-Ranking-Id", rank.toString(), "X-Role", "ROLE_ADM");
    var absent = leave(UUID.randomUUID(), outsider.token(), forged);
    var foreign = leave(rank, outsider.token(), forged);
    assertThat(absent.status()).isEqualTo(404); assertThat(foreign.status()).isEqualTo(404);
    assertThat(foreign.body()).isEqualTo(absent.body());
    var ownerResponse = leave(rank, owner.token(), forged);
    assertThat(ownerResponse.status()).isEqualTo(409);
    assertThat(ownerResponse.body().required("code").asText()).isEqualTo("ranking.owner_exit_requires_destination");
    assertThat(authorizationRows()).isEqualTo(before);
    assertLeft(leave(rank, member.token(), Map.of()));
    assertThat(leave(rank, null, Map.of()).status()).isIn(401,403);
  }

  @Test void simultaneousDeparturesAreIdempotentAndDoNotTouchOtherMemberships() throws Exception {
    var owner = user(false); var member = user(false); var other = user(false);
    UUID rank = ranking(owner, "PRIVATE"); membership(rank, member, "READ"); membership(rank, other, "READ");
    var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    for (int i=0;i<6;i++) futures.add(http.sendAsync(deleteRequest(rank, member.token(), Map.of()), HttpResponse.BodyHandlers.ofString()));
    for (var future : futures) assertLeft(response(future.get(15, java.util.concurrent.TimeUnit.SECONDS)));
    assertThat(jdbc.queryForObject("select count(*) from " + table("ranking_membership") + " where ranking_id=? and active", Integer.class, rank)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from " + table("ranking_membership_grant") + " where ranking_id=?", Integer.class, rank)).isEqualTo(2);
  }

  @Test void concurrentOwnershipChangeIsRecheckedAfterRankingLock() throws Exception {
    var owner = user(false); var member = user(false);
    UUID rank = ranking(owner, "PRIVATE"); membership(rank, member, "READ");
    java.util.concurrent.CompletableFuture<HttpResponse<String>> pending;
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("update " + table("ranking") + " set owner_user_id=? where id=?")) {
        statement.setObject(1, member.id()); statement.setObject(2, rank); statement.executeUpdate();
        pending = http.sendAsync(deleteRequest(rank, member.token(), Map.of()), HttpResponse.BodyHandlers.ofString());
        int blockerPid;
        try (var pidQuery = connection.createStatement(); var pidRow = pidQuery.executeQuery("select pg_backend_pid()")) {
          pidRow.next(); blockerPid = pidRow.getInt(1);
        }
        awaitDepartureWaitingOnRankingLock(blockerPid);
        assertThat(pending.isDone()).isFalse();
        connection.commit();
      }
    }
    var result = response(pending.get(15, java.util.concurrent.TimeUnit.SECONDS));
    assertThat(result.status()).isEqualTo(409);
    assertThat(result.body().required("code").asText()).isEqualTo("ranking.owner_exit_requires_destination");
    assertThat(jdbc.queryForObject("select active from " + table("ranking_membership") + " where ranking_id=? and user_id=?", Boolean.class, rank, member.id())).isTrue();
  }

  private void awaitDepartureWaitingOnRankingLock(int blockerPid) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      boolean waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists (
            select 1 from pg_stat_activity activity
            where activity.datname = current_database()
              and activity.wait_event_type = 'Lock'
              and ? = any(pg_blocking_pids(activity.pid))
              and activity.query like 'select owner_user_id from ranking where id=%for update%'
              and exists (
                select 1 from pg_locks held
                where held.pid = activity.pid and held.relation = cast(? as regclass)
              )
          )
          """, Boolean.class, blockerPid, table("ranking")));
      if (waiting) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Departure request did not reach the PostgreSQL ranking lock barrier");
  }

  @Test void committedDeparturePreventsLaterTransferToInactiveMember() throws Exception {
    var owner = user(false); var member = user(false);
    UUID rank = ranking(owner, "PRIVATE"); membership(rank, member, "READ");
    assertLeft(leave(rank, member.token(), Map.of()));
    var before = authorizationRows();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("update " + table("ranking") + " set owner_user_id=? where id=?")) {
        statement.setObject(1, member.id()); statement.setObject(2, rank);
        statement.executeUpdate();
        var violation = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, connection::commit);
        assertThat(violation.getSQLState()).isEqualTo("23503");
        connection.rollback();
      }
    }
    assertThat(authorizationRows()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select owner_user_id from " + table("ranking") + " where id=?", UUID.class, rank)).isEqualTo(owner.id());
    assertThat(jdbc.queryForObject("select active from " + table("ranking_membership") + " where ranking_id=? and user_id=?", Boolean.class, rank, member.id())).isFalse();
  }

  @Test void lockedAccountCannotLeaveWithOldToken() throws Exception {
    var owner = user(false); var member = user(false);
    UUID rank = ranking(owner, "PRIVATE"); membership(rank, member, "READ");
    jdbc.update("update " + table("user_statistics") + " set consecutive_failed_login_attempts=10 where user_id=?", member.id());
    var before = authorizationRows();
    assertThat(leave(rank, member.token(), Map.of()).status()).isIn(401, 403);
    assertThat(authorizationRows()).isEqualTo(before);
  }

  private HttpRequest deleteRequest(UUID ranking, String token, Map<String,String> headers) {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/monolith/rankings/" + ranking + "/membership/self"))
        .timeout(Duration.ofSeconds(10)).DELETE().header("Accept", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    headers.forEach(builder::header);
    return builder.build();
  }
  private ApiResponse leave(UUID rank, String token, Map<String,String> headers) throws Exception {
    return response(http.send(deleteRequest(rank, token, headers), HttpResponse.BodyHandlers.ofString()));
  }
  private void assertLeft(ApiResponse response) {
    assertThat(response.status()).isEqualTo(204);
    assertThat(response.body().isNull()).isTrue();
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
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
