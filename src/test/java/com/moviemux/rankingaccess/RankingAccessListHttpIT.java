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
class RankingAccessListHttpIT {
  private static final String SCHEMA = "ranking_list_http_test_" + UUID.randomUUID().toString().replace("-", "");
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
    properties.add("ranking.creation.max-owned", () -> "1");
    properties.add("ranking.creation.include-archived", () -> "true");
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

  @Test void paginationFiltersBeforePagingAndKeepsAllGrantsWithoutDuplicates() throws Exception {
    var owner = user(false);
    var member = user(false);
    var expected = new ArrayList<String>();
    for (int i = 0; i < 7; i++) {
      UUID id = ranking(owner, "PRIVATE");
      membership(id, member, "READ", "RATE_OWN", "ADD_ENTRY", "INVITE_MEMBER");
      expected.add(id.toString());
    }
    UUID noRead = ranking(owner, "PUBLIC");
    membership(noRead, member, "RATE_OWN");
    UUID revoked = ranking(owner, "PRIVATE");
    membership(revoked, member, "READ");
    jdbc.update("update " + table("ranking_membership") + " set active=false where ranking_id=? and user_id=?", revoked, member.id());
    ranking(owner, "PUBLIC");
    expected.sort(String::compareTo);
    var before = authorizationRows();
    var actual = new ArrayList<String>();
    String cursor = null;
    int pages = 0;
    do {
      var response = list(member, "?limit=2" + (cursor == null ? "" : "&after=" + cursor));
      assertThat(response.status()).isEqualTo(200);
      assertThat(fields(response.body())).containsExactlyInAnyOrder("items", "next_cursor");
      assertThat(response.body().required("items").size()).isBetween(1, 2);
      for (var item : response.body().required("items")) {
        assertThat(fields(item)).containsExactlyInAnyOrder("ranking_id", "capabilities");
        actual.add(item.required("ranking_id").asText());
        assertThat(item.required("capabilities").size()).isEqualTo(4);
      }
      var next = response.body().required("next_cursor");
      cursor = next.isNull() ? null : next.asText();
      if (cursor != null) assertThat(cursor).isEqualTo(actual.get(actual.size()-1));
      assertThat(++pages).isLessThanOrEqualTo(4);
    } while (cursor != null);
    assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    assertThat(authorizationRows()).isEqualTo(before);
  }

  @Test void revokedCursorAndInaccessibleCursorRemainOnlyExclusivePositionBounds() throws Exception {
    var owner = user(false);
    var member = user(false);
    var ranks = new ArrayList<UUID>();
    for (int i = 0; i < 5; i++) ranks.add(ranking(owner, "PRIVATE"));
    ranks.sort(java.util.Comparator.comparing(UUID::toString));
    // The middle rank is never accessible to this actor, including as a cursor.
    for (int i : new int[]{0, 1, 3, 4}) membership(ranks.get(i), member, "READ", "RATE_OWN");
    var first = list(member, "?limit=2");
    assertThat(first.status()).isEqualTo(200);
    assertThat(itemIds(first)).containsExactly(ranks.get(0).toString(), ranks.get(1).toString());
    String cursor = first.body().required("next_cursor").asText();
    assertThat(cursor).isEqualTo(ranks.get(1).toString());
    jdbc.update("update " + table("ranking_membership") + " set active=false where ranking_id=? and user_id=?",
        ranks.get(1), member.id());
    var before = authorizationRows();
    var next = list(member, "?limit=2&after=" + cursor);
    assertThat(next.status()).isEqualTo(200);
    assertThat(itemIds(next)).containsExactly(ranks.get(3).toString(), ranks.get(4).toString());
    assertThat(next.body().required("next_cursor").isNull()).isTrue();
    for (int repeat = 0; repeat < 2; repeat++) {
      var inaccessibleCursor = list(member, "?limit=2&after=" + ranks.get(2));
      assertThat(inaccessibleCursor.status()).isEqualTo(200);
      assertThat(inaccessibleCursor.body()).isEqualTo(next.body());
      for (var item : inaccessibleCursor.body().required("items"))
        assertThat(fields(item)).containsExactlyInAnyOrder("ranking_id", "capabilities");
    }
    assertThat(itemIds(list(member, ""))).containsExactly(
        ranks.get(0).toString(), ranks.get(3).toString(), ranks.get(4).toString());
    assertThat(authorizationRows()).isEqualTo(before);
  }

  private static List<String> itemIds(ApiResponse response) {
    var ids = new ArrayList<String>();
    response.body().required("items").forEach(item -> ids.add(item.required("ranking_id").asText()));
    return ids;
  }

  @Test void sameTokenReflectsRevocationAndOwnersNeedNoExplicitReadGrant() throws Exception {
    var owner = user(false);
    var member = user(false);
    UUID id = ranking(owner, "PRIVATE");
    membership(id, member, "READ", "RATE_OWN");
    assertThat(list(owner, "").body().at("/items/0/capabilities").size()).isEqualTo(RankingCapability.values().length);
    assertThat(list(member, "").body().required("items").size()).isEqualTo(1);
    jdbc.update("delete from " + table("ranking_membership_grant") + " where ranking_id=? and user_id=? and capability='READ'", id, member.id());
    assertEmpty(list(member, ""));
    grant(id, member, "READ");
    assertThat(list(member, "").body().required("items").size()).isEqualTo(1);
    jdbc.update("update " + table("ranking_membership") + " set active=false where ranking_id=? and user_id=?", id, member.id());
    assertEmpty(list(member, ""));
    assertEmpty(list(user(true), ""));
    assertEmpty(list(user(false), ""));
  }

  @Test void defaultPageIsTwentyAndLockedAccountCannotReuseToken() throws Exception {
    var owner = user(false);
    for (int i = 0; i < 21; i++) ranking(owner, "PRIVATE");
    var page = list(owner, "").body();
    assertThat(page.required("items").size()).isEqualTo(20);
    assertThat(page.required("next_cursor").isTextual()).isTrue();
    assertThat(list(owner, "?after=" + page.required("next_cursor").asText()).body().required("items").size()).isEqualTo(1);
    jdbc.update("update " + table("user_statistics") + " set consecutive_failed_login_attempts=10 where user_id=?", owner.id());
    var denied = list(owner, "");
    assertThat(denied.status()).isIn(401, 403);
  }

  @Test void technicalBoundsMalformedQueriesAndEmptyTail() throws Exception {
    var owner = user(false);
    UUID id = ranking(owner, "PRIVATE");
    for (String query : List.of("?limit=0", "?limit=-1", "?limit=101", "?limit=word", "?after=wrong")) {
      var response = list(owner, query);
      assertThat(response.status()).isEqualTo(400);
      assertThat(response.body().required("code").asText()).isEqualTo("ranking.invalid_page");
    }
    assertThat(list(owner, "?limit=100").body().required("items").size()).isEqualTo(1);
    assertEmpty(list(owner, "?after=" + id));
    assertThat(request("/rankings/access", null, Map.of()).status()).isIn(401, 403);
  }

  @Test void directoryCreatesAtomicallyAndEnforcesConcurrentOwnerQuota() throws Exception {
    var owner = user(false);
    var other = user(true);
    var json = """
        {"name":"  Cinema com amigos  ","visibility":"PRIVATE","admission":"INVITATION"}
        """;
    var first = http.sendAsync(createRequest(owner, json), HttpResponse.BodyHandlers.ofString());
    var second = http.sendAsync(createRequest(owner, json), HttpResponse.BodyHandlers.ofString());
    var responses = List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
        second.get(15, java.util.concurrent.TimeUnit.SECONDS));
    assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
    var created = JSON.readTree(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow().body());
    var conflict = JSON.readTree(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow().body());
    assertThat(conflict.required("code").asText()).isEqualTo("ranking.creation_limit_reached");
    assertThat(fields(created)).containsExactlyInAnyOrder("ranking_id", "name", "visibility", "admission", "capabilities");
    assertThat(created.required("name").asText()).isEqualTo("Cinema com amigos");
    assertThat(created.required("capabilities").size()).isEqualTo(RankingCapability.values().length);
    UUID id = UUID.fromString(created.required("ranking_id").asText());
    assertThat(jdbc.queryForObject("select count(*) from ranking where id=? and owner_user_id=?", Long.class, id, owner.id())).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership where ranking_id=? and user_id=? and active",
        Long.class, id, owner.id())).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership_grant where ranking_id=?",
        Long.class, id)).isZero();

    var mine = request("/rankings", owner.token(), Map.of());
    assertThat(mine.status()).isEqualTo(200);
    assertThat(mine.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    assertThat(mine.body().required("items").get(0)).isEqualTo(created);
    assertThat(mine.body().required("next_cursor").isNull()).isTrue();
    assertEmpty(request("/rankings", other.token(), Map.of()));
    jdbc.update("update ranking set archived_at=now() where id=?", id);
    assertThat(http.send(createRequest(owner, json), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
    assertThat(http.send(createRequest(other, json), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
  }

  @Test void directoryRejectsForgedFieldsAndDoesNotDiscloseRevokedNames() throws Exception {
    var owner = user(false);
    var member = user(false);
    var id = ranking(owner, "PUBLIC");
    membership(id, member, "READ", "ADD_ENTRY");
    var read = request("/rankings", member.token(), Map.of());
    assertThat(read.body().at("/items/0").required("name").isNull()).isTrue();
    jdbc.update("update ranking set name='Private context for members',archived_at=now() where id=?", id);
    read = request("/rankings", member.token(), Map.of());
    assertThat(read.body().at("/items/0/name").asText()).isEqualTo("Private context for members");
    assertThat(read.body().at("/items/0/capabilities/0").asText()).isEqualTo("READ");
    assertThat(read.body().at("/items/0/capabilities").size()).isEqualTo(1);
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'", id, member.id());
    assertEmpty(request("/rankings", member.token(), Map.of()));
    assertThat(request("/rankings", null, Map.of()).status()).isIn(401, 403);
    assertThat(request("/rankings?after=wrong", owner.token(), Map.of()).body().required("code").asText())
        .isEqualTo("ranking.invalid_page");

    var bad = http.send(createRequest(owner, """
        {"name":"ignored","visibility":"PRIVATE","admission":"INVITATION","owner_user_id":"forged"}
        """), HttpResponse.BodyHandlers.ofString());
    assertThat(bad.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(bad.body()).required("code").asText()).isEqualTo("ranking.invalid_creation");
  }

  private HttpRequest createRequest(Account actor, String json) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/monolith/rankings"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + actor.token())
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
  }

  private ApiResponse list(Account actor, String query) throws Exception {
    var response = request("/rankings/access" + query, actor.token(), Map.of());
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    return response;
  }

  private void assertEmpty(ApiResponse response) {
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().required("items").size()).isZero();
    assertThat(response.body().required("next_cursor").isNull()).isTrue();
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
