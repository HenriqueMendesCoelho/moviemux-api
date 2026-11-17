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
import java.util.Arrays;
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
class RankingAccessHttpIT {
  private static final String SCHEMA = "ranking_http_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test
  void memberGrantsRemainRankingScopedAndOwnerGetsOnlyLocalCapabilities() throws Exception {
    var ownerA = user(false);
    var ownerB = user(false);
    var member = user(false);
    UUID a = ranking(ownerA, "PRIVATE");
    UUID b = ranking(ownerB, "PRIVATE");
    membership(a, member, "READ", "RATE_OWN");
    membership(b, member, "READ", "ADD_ENTRY");

    var before = authorizationRows();
    assertAccess(get(a, member.token()), a, "READ", "RATE_OWN");
    assertAccess(get(b, member.token()), b, "READ", "ADD_ENTRY");
    assertAccess(get(a, ownerA.token()), a,
        Arrays.stream(RankingCapability.values()).map(Enum::name).toArray(String[]::new));
    assertNotFound(get(b, ownerA.token()));
    assertNotFound(get(a, ownerB.token()));
    assertThat(authorizationRows()).isEqualTo(before);
  }

  @Test
  void inaccessibleAndMissingRankingsHaveIdenticalNonEnumeratingResponses() throws Exception {
    var owner = user(false);
    var outsider = user(false);
    UUID privateRanking = ranking(owner, "PRIVATE");
    var denied = get(privateRanking, outsider.token());
    var missing = get(UUID.randomUUID(), outsider.token());
    assertNotFound(denied);
    assertNotFound(missing);
    assertThat(denied.body()).isEqualTo(missing.body());
    assertThat(denied.headers().firstValue("Cache-Control")).isEqualTo(missing.headers().firstValue("Cache-Control"));
    assertThat(denied.body().toString()).doesNotContain(owner.id().toString(), owner.email(), privateRanking.toString());
  }

  @Test
  void publicVisibilityIsNotMembershipOrAnAnonymousPrivateProjection() throws Exception {
    var owner = user(false);
    var outsider = user(false);
    UUID publicRanking = ranking(owner, "PUBLIC");
    assertNotFound(get(publicRanking, outsider.token()));
    assertThat(get(publicRanking, null).status()).isIn(401, 403);
    assertThat(get(publicRanking, "not-a-valid-jwt").status()).isIn(401, 403);
    assertAccess(get(publicRanking, owner.token()), publicRanking,
        Arrays.stream(RankingCapability.values()).map(Enum::name).toArray(String[]::new));
  }

  @Test
  void globalAdminAndForgedClaimsOrHeadersCannotBorrowOwnership() throws Exception {
    var owner = user(false);
    var outsider = user(false);
    var globalAdmin = user(true);
    UUID ranking = ranking(owner, "PRIVATE");
    assertNotFound(get(ranking, globalAdmin.token()));
    // The token is validly signed for the outsider's subject but lies about identity/roles.
    String forgedClaims = jwt.generateToken(owner.id(), outsider.email(), "Synthetic forged name",
        Set.of("ROLE_ADM", "ROLE_USER"));
    var forged = request(path(ranking) + "?actorId=" + owner.id() + "&userId=" + owner.id()
            + "&capabilities=READ,MANAGE_MEMBERS", forgedClaims,
        Map.of("X-User-Id", owner.id().toString(), "X-Ranking-Role", "OWNER", "X-Ranking-Id", ranking.toString()));
    assertNotFound(forged);
    assertNotFound(get(ranking, outsider.token()));
  }

  @Test
  void sameValidTokenObservesCurrentGrantsAndMembershipAfterWarmReads() throws Exception {
    var owner = user(false);
    var member = user(false);
    UUID ranking = ranking(owner, "PRIVATE");
    membership(ranking, member, "READ", "RATE_OWN");
    String originalToken = member.token();
    assertAccess(get(ranking, originalToken), ranking, "READ", "RATE_OWN");

    jdbc.update("delete from " + table("ranking_membership_grant")
        + " where ranking_id=? and user_id=? and capability='READ'", ranking, member.id());
    assertNotFound(get(ranking, originalToken));
    grant(ranking, member, "READ");
    assertAccess(get(ranking, originalToken), ranking, "READ", "RATE_OWN");
    jdbc.update("update " + table("ranking_membership") + " set active=false where ranking_id=? and user_id=?",
        ranking, member.id());
    assertNotFound(get(ranking, originalToken));
    jdbc.update("update " + table("ranking_membership") + " set active=true where ranking_id=? and user_id=?",
        ranking, member.id());
    jdbc.update("delete from " + table("ranking_membership_grant")
        + " where ranking_id=? and user_id=? and capability='RATE_OWN'", ranking, member.id());
    assertAccess(get(ranking, originalToken), ranking, "READ");
  }

  @Test
  void currentlyLockedAccountCannotReusePreviouslyValidToken() throws Exception {
    var owner = user(false);
    UUID ranking = ranking(owner, "PRIVATE");
    String originalToken = owner.token();
    assertAccess(get(ranking, originalToken), ranking,
        Arrays.stream(RankingCapability.values()).map(Enum::name).toArray(String[]::new));
    jdbc.update("update " + table("user_statistics") + " set consecutive_failed_login_attempts=10 where user_id=?", owner.id());
    assertNotFound(get(ranking, originalToken));
  }

  @Test
  void concurrentPrincipalsAndRankingsNeverSharePrivateAccessState() throws Exception {
    var ownerA = user(false);
    var ownerB = user(false);
    var memberA = user(false);
    var memberB = user(false);
    UUID a = ranking(ownerA, "PRIVATE");
    UUID b = ranking(ownerB, "PRIVATE");
    membership(a, memberA, "READ", "RATE_OWN");
    membership(b, memberB, "READ", "ADD_ENTRY");
    var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < 4; i++) {
      futures.add(http.sendAsync(httpRequest(path(a), memberA.token(), Map.of()), HttpResponse.BodyHandlers.ofString()));
      futures.add(http.sendAsync(httpRequest(path(b), memberB.token(), Map.of()), HttpResponse.BodyHandlers.ofString()));
      futures.add(http.sendAsync(httpRequest(path(b), memberA.token(), Map.of()), HttpResponse.BodyHandlers.ofString()));
      futures.add(http.sendAsync(httpRequest(path(a), memberB.token(), Map.of()), HttpResponse.BodyHandlers.ofString()));
    }
    for (int i = 0; i < futures.size(); i += 4) {
      assertAccess(response(futures.get(i).join()), a, "READ", "RATE_OWN");
      assertAccess(response(futures.get(i + 1).join()), b, "READ", "ADD_ENTRY");
      assertNotFound(response(futures.get(i + 2).join()));
      assertNotFound(response(futures.get(i + 3).join()));
    }
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
  private static String path(UUID ranking) { return "/rankings/" + ranking + "/access"; }
  private ApiResponse get(UUID ranking, String token) throws Exception { return request(path(ranking), token, Map.of()); }

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

  private static void assertAccess(ApiResponse response, UUID ranking, String... capabilities) {
    assertThat(response.status()).isEqualTo(200);
    assertThat(fields(response.body())).containsExactlyInAnyOrder("ranking_id", "capabilities");
    assertThat(response.body().required("ranking_id").asText()).isEqualTo(ranking.toString());
    var grants = new ArrayList<String>();
    response.body().required("capabilities").forEach(node -> grants.add(node.asText()));
    assertThat(grants).containsExactlyInAnyOrder(capabilities);
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
  }

  private static void assertNotFound(ApiResponse response) {
    assertThat(response.status()).isEqualTo(404);
    assertThat(fields(response.body())).containsExactly("code");
    assertThat(response.body().required("code").asText()).isEqualTo("ranking.not_found");
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
  }

  private static Set<String> fields(JsonNode node) {
    var fields = new HashSet<String>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private record Account(UUID id, String email, String token) {}
  private record ApiResponse(int status, JsonNode body, HttpHeaders headers) {}
}
