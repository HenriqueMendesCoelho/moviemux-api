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
  private static final AtomicInteger TMDB_CALLS = new AtomicInteger();
  private static volatile java.util.concurrent.CountDownLatch providerArrived, releaseProvider;
  private static String databaseUrl, databaseUser, databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalStub;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JWTUtil jwt;
  @Autowired private com.moviemux.movie.domain.repository.MovieReadLookup movieReadLookup;
  @Autowired private com.moviemux.movie.adapter.repository.postgres.MovieActivityJpaReader movieActivityReader;
  @Autowired private com.moviemux.movie.domain.repository.MovieStatisticsReader movieStatisticsReader;
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
    properties.add("ranking.invitation.address.digest-key",
        () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    properties.add("ranking.invitation.address.encryption-key",
        () -> java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    properties.add("ranking.catalog.tmdb-import-enabled", () -> "true");
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
      String base = "/api/v1/tmdb/movie/";
      String path = exchange.getRequestURI().getPath();
      if (path.matches("/api/v1/tmdb/movie/82000[1-7]/summary")) {
        TMDB_CALLS.incrementAndGet();
        int id = Integer.parseInt(path.substring(base.length(), path.length() - "/summary".length()));
        int status = id == 820004 ? 404 : id == 820005 ? 429 : 200;
        if (id == 820007) {
          var arrived = providerArrived;
          var release = releaseProvider;
          if (arrived == null || release == null) status = 500;
          else {
            arrived.countDown();
            try {
              if (!release.await(6, java.util.concurrent.TimeUnit.SECONDS)) status = 500;
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              status = 500;
            }
          }
        }
        String value = status == 200 ? JSON.createObjectNode().put("tmdb_id", id == 820003 ? id+999 : id)
            .put("original_title","Synthetic KIT title").put("imdb_id",id == 820006 ? "tt900000" : "tt" + id)
            .put("runtime",93).toString() : "synthetic-private-upstream-body";
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return;
      }
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
    TMDB_CALLS.set(0);
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

  @Test void contextualSummaryMatchesDirectoryAndDoesNotRevealMissingVersusPrivate() throws Exception {
    var owner = user(false);
    var member = user(false);
    var outsider = user(true);
    var rank = ranking(owner, "PUBLIC");
    membership(rank, member, "READ", "ADD_ENTRY");
    var endpoint = "/rankings/" + rank;
    var detail = request(endpoint, member.token(), Map.of());
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body()).isEqualTo(request("/rankings", member.token(), Map.of()).body().required("items").get(0));
    assertThat(detail.body().required("name").isNull()).isTrue();
    jdbc.update("update ranking set name='Artes e filmes',archived_at=now() where id=?", rank);
    detail = request(endpoint, member.token(), Map.of());
    assertThat(detail.body().required("name").asText()).isEqualTo("Artes e filmes");
    assertThat(detail.body().required("capabilities").size()).isEqualTo(1);
    assertThat(detail.body().at("/capabilities/0").asText()).isEqualTo("READ");
    var denied = request(endpoint, outsider.token(), Map.of());
    assertThat(denied.status()).isEqualTo(404);
    assertThat(denied.body().required("code").asText()).isEqualTo("ranking.not_found");
    assertThat(denied.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    assertThat(request("/rankings/wrong", outsider.token(), Map.of()).body()).isEqualTo(denied.body());
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'", rank, member.id());
    assertThat(request(endpoint, member.token(), Map.of()).body()).isEqualTo(denied.body());
  }

  @Test void privateInvitationIsBoundToRecipientAndOnlyAddsDefaultGrantsOnce() throws Exception {
    var owner = user(false);
    var recipient = user(false);
    var outsider = user(true);
    UUID rank = ranking(owner, "PRIVATE");
    UUID anotherRank = ranking(owner, "PRIVATE");
    var code = rotateInvitationAddress(recipient);
    var issued = inviteRequest(owner, "POST", rank, "", code);
    assertThat(issued.statusCode()).isEqualTo(201);
    assertThat(issued.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    var receipt = JSON.readTree(issued.body());
    assertThat(fields(receipt)).containsExactlyInAnyOrder("invite_id", "expires_at");
    var id = UUID.fromString(receipt.required("invite_id").asText());
    assertThat(jdbc.queryForObject("select extract(epoch from expires_at-issued_at)::bigint from ranking_invitation where invite_id=?",
        Long.class, id)).isEqualTo(604800L);
    assertThat(inviteRequest(owner, "POST", rank, "", code).statusCode()).isEqualTo(409);
    assertInvitationMissing(inviteRequest(outsider, "POST", rank, "/" + id + "/accept", null));
    assertInvitationMissing(inviteRequest(recipient, "POST", anotherRank, "/" + id + "/accept", null));
    assertEmpty(request("/rankings", recipient.token(), Map.of()));
    assertThat(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null).statusCode()).isEqualTo(204);
    assertThat(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null).statusCode()).isEqualTo(204);
    assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?",
        String.class, rank, recipient.id())).containsExactlyInAnyOrder("READ", "RATE_OWN");
    assertInvitationMissing(inviteRequest(owner, "DELETE", rank, "/" + id, null));
    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?", rank, recipient.id());
    assertInvitationMissing(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null));
    assertInvitationMissing(inviteRequest(owner, "POST", rank, "", code));
    assertEmpty(request("/rankings", recipient.token(), Map.of()));
  }

  @Test void invitationRevocationAndAuthorityLossPreventAcceptanceAndDoNotLeakScope() throws Exception {
    var owner = user(false);
    var inviter = user(false);
    var otherInviter = user(false);
    var recipient = user(false);
    UUID rank = ranking(owner, "PUBLIC");
    membership(rank, inviter, "INVITE_MEMBER");
    membership(rank, otherInviter, "INVITE_MEMBER");
    var code = rotateInvitationAddress(recipient);
    var issued = inviteRequest(inviter, "POST", rank, "", code);
    assertThat(issued.statusCode()).isEqualTo(201);
    var id = JSON.readTree(issued.body()).required("invite_id").asText();
    assertInvitationMissing(inviteRequest(otherInviter, "DELETE", rank, "/" + id, null));
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='INVITE_MEMBER'",
        rank, inviter.id());
    assertInvitationMissing(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null));
    assertThat(inviteRequest(owner, "DELETE", rank, "/" + id, null).statusCode()).isEqualTo(204);
    assertThat(inviteRequest(owner, "DELETE", rank, "/" + id, null).statusCode()).isEqualTo(204);
    assertInvitationMissing(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null));
    var reissued = inviteRequest(owner, "POST", rank, "", code);
    assertThat(reissued.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(reissued.body()).required("invite_id").asText()).isNotEqualTo(id);
    assertEmpty(request("/rankings", recipient.token(), Map.of()));
  }

  @Test void simultaneousInvitationAcceptOrRevokeHasOneAtomicWinner() throws Exception {
    var owner = user(false);
    var recipient = user(false);
    UUID rank = ranking(owner, "PRIVATE");
    var code = rotateInvitationAddress(recipient);
    var firstIssue = inviteRequest(owner, "POST", rank, "", code);
    var id = JSON.readTree(firstIssue.body()).required("invite_id").asText();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var accept = executor.submit(() -> {
        start.await();
        return inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null).statusCode();
      });
      var revoke = executor.submit(() -> {
        start.await();
        return inviteRequest(owner, "DELETE", rank, "/" + id, null).statusCode();
      });
      start.countDown();
      int accepted = accept.get(15, java.util.concurrent.TimeUnit.SECONDS);
      int revoked = revoke.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(List.of(accepted, revoked)).containsExactlyInAnyOrder(204, 404);
      assertThat(jdbc.queryForObject(
          "select count(*) from ranking_membership where ranking_id=? and user_id=?", Long.class, rank, recipient.id()))
          .isEqualTo(accepted == 204 ? 1L : 0L);
      assertThat(jdbc.queryForObject(
          "select count(*) from ranking_membership_grant where ranking_id=? and user_id=?", Long.class, rank, recipient.id()))
          .isEqualTo(accepted == 204 ? 2L : 0L);
    }
  }

  @Test void addressIsPrivateAndRotationOnlyInvalidatesFutureIssuance() throws Exception {
    var host = user(false);
    var recipient = user(false);
    var foreign = user(false);
    UUID rank = ranking(host, "PRIVATE");
    UUID otherRank = ranking(host, "PRIVATE");
    String path = "/user/ranking-invitation-address";
    assertThat(request(path, recipient.token(), Map.of()).body().required("recipient_code").isNull()).isTrue();
    String firstCode = rotateInvitationAddress(recipient);
    assertThat(request(path, recipient.token(), Map.of()).body().required("recipient_code").asText()).isEqualTo(firstCode);
    assertThat(request(path, foreign.token(), Map.of()).body().required("recipient_code").isNull()).isTrue();
    assertThat(request(path, null, Map.of()).status()).isIn(401, 403);
    var oldBody = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/api/monolith/rankings/" + rank + "/invitations"))
        .header("Authorization", "Bearer " + host.token()).header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(
            JSON.createObjectNode().put("user_id", recipient.id().toString()).toString())).build();
    assertThat(http.send(oldBody, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    var invite = inviteRequest(host, "POST", rank, "", firstCode);
    assertThat(invite.statusCode()).isEqualTo(201);
    String newCode = rotateInvitationAddress(recipient);
    assertThat(newCode).isNotEqualTo(firstCode);
    assertInvitationMissing(inviteRequest(host, "POST", otherRank, "", firstCode));
    assertThat(inviteRequest(host, "POST", otherRank, "", newCode).statusCode()).isEqualTo(201);
    var id = JSON.readTree(invite.body()).required("invite_id").asText();
    assertThat(inviteRequest(recipient, "POST", rank, "/" + id + "/accept", null).statusCode()).isEqualTo(204);
  }

  @Test void privateInboxFiltersBeforePaginationAndRechecksIssuerOnSameToken() throws Exception {
    var owner = user(false);
    var issuer = user(false);
    var recipient = user(false);
    var outsider = user(true);
    String code = rotateInvitationAddress(recipient);
    UUID firstRank = ranking(owner, "PRIVATE");
    UUID secondRank = ranking(owner, "PRIVATE");
    membership(firstRank, issuer, "INVITE_MEMBER");
    membership(secondRank, issuer, "INVITE_MEMBER");
    jdbc.update("update ranking set name='Fantastic Cinema' where id=?", firstRank);
    var first = inviteRequest(issuer, "POST", firstRank, "", code);
    var second = inviteRequest(issuer, "POST", secondRank, "", code);
    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(second.statusCode()).isEqualTo(201);
    String path = "/ranking-invitations/incoming";
    var outsiderPage = request(path, outsider.token(), Map.of("X-User-Id", recipient.id().toString()));
    assertEmpty(outsiderPage);
    assertThat(request(path, null, Map.of()).status()).isIn(401,403);
    var page = request(path + "?limit=1", recipient.token(), Map.of());
    assertThat(page.status()).isEqualTo(200);
    assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    String cursor = page.body().required("next_cursor").asText();
    assertThat(page.body().required("items").size()).isEqualTo(1);
    assertThat(page.body().at("/items/0/invite_id").asText()).isEqualTo(cursor);
    var tail = request(path + "?limit=1&after=" + cursor, recipient.token(), Map.of());
    assertThat(tail.body().required("next_cursor").isNull()).isTrue();
    assertThat(tail.body().required("items").size()).isEqualTo(1);
    assertThat(tail.body().at("/items/0/invite_id").asText()).isNotEqualTo(cursor);

    var every = request(path, recipient.token(), Map.of()).body().required("items");
    assertThat(every).hasSize(2);
    for (var row : every) {
      assertThat(fields(row)).containsExactlyInAnyOrder("invite_id", "ranking_id", "ranking_name", "expires_at");
      if (row.required("ranking_id").asText().equals(firstRank.toString()))
        assertThat(row.required("ranking_name").asText()).isEqualTo("Fantastic Cinema");
      else assertThat(row.required("ranking_name").isNull()).isTrue();
    }

    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='INVITE_MEMBER'",
        firstRank, issuer.id());
    var remaining = request(path, recipient.token(), Map.of());
    assertThat(remaining.body().required("items").size()).isEqualTo(1);
    assertThat(remaining.body().at("/items/0/ranking_id").asText()).isEqualTo(secondRank.toString());
    var targetId = remaining.body().at("/items/0/invite_id").asText();
    assertThat(inviteRequest(recipient, "POST", secondRank, "/" + targetId + "/accept", null).statusCode()).isEqualTo(204);
    assertEmpty(request(path, recipient.token(), Map.of()));
  }

  @Test void canonicalContextMovieDoesNotAppearInLegacyReadsOrWrites() throws Exception {
    var owner = user(true);
    var outsider = user(false);
    var legacy = UUID.randomUUID();
    var contextual = UUID.randomUUID();
    long legacyTmdb = 810001L, contextualTmdb = 810002L;
    for (boolean eligible : List.of(true,false)) {
      jdbc.update("""
          insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,
            show_notes,tmdb_id,legacy_eligible,runtime)
          values (?,now(),'Film description','Director',?,?,?,true,?,?,90)
          """, eligible ? legacy : contextual, eligible ? "Synthetic Legacy" : "Synthetic Context",
          eligible ? "Synthetic Legacy" : "Synthetic Context", owner.id(), eligible ? legacyTmdb : contextualTmdb, eligible);
    }
    jdbc.update("insert into movie_note(movie_id,user_id,note,created_at) values (?,?,8,now())",legacy,owner.id());
    jdbc.update("insert into movie_genre(id,name,tmdb_id) values (8001,'Only contextual',8001),(8002,'Shared',8002)");
    jdbc.update("insert into movies_genres(genre_id,movie_id) values (8001,?),(8002,?),(8002,?)",
        contextual, legacy, contextual);
    var list = request("/movie", owner.token(), Map.of());
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().required("total_elements").asInt()).isEqualTo(1);
    assertThat(list.body().required("content").get(0).required("id").asText()).isEqualTo(legacy.toString());
    var hidden = request("/movie/" + contextual, owner.token(), Map.of());
    var missing = request("/movie/" + UUID.randomUUID(), owner.token(), Map.of());
    assertThat(hidden.status()).isEqualTo(missing.status());
    assertThat(request("/movie/note/" + contextual, owner.token(), Map.of()).status()).isEqualTo(400);
    var facets = request("/movie/genres-with-movies", owner.token(), Map.of());
    assertThat(facets.status()).isEqualTo(200);
    assertThat(facets.body().size()).isEqualTo(1);
    assertThat(facets.body().get(0).required("id").asLong()).isEqualTo(8002L);
    var stats = request("/statistic/movies", owner.token(), Map.of()).body();
    assertThat(stats.required("total_number_of_movies").asInt()).isEqualTo(1);
    assertThat(movieReadLookup.listAll().stream().map(entry -> entry.id()).toList()).containsExactly(legacy);
    assertThat(movieActivityReader.ownedMovies(owner.id())).containsExactly(legacy);
    assertThat(movieStatisticsReader.genres().stream().map(genre -> genre.numberOfMovies()).sorted().toList())
        .containsExactly(0,1);

    var legacyCollision = legacyMovieCreate(owner, legacyTmdb);
    var contextCollision = legacyMovieCreate(owner, contextualTmdb);
    assertThat(legacyCollision.statusCode()).isEqualTo(400);
    assertThat(contextCollision.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(contextCollision.body())).isEqualTo(JSON.readTree(legacyCollision.body()));
    assertThat(jdbc.queryForObject("select count(*) from movie", Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select legacy_eligible from movie where id=?",Boolean.class,contextual)).isFalse();

    UUID rank = ranking(owner, "PRIVATE");
    var add = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/entries/catalog"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner.token())
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
            JSON.createObjectNode().put("movie_id",contextual.toString()).toString())).build();
    assertThat(http.send(add,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
    assertThat(request("/rankings/" + rank + "/entries", owner.token(), Map.of()).body().at("/items/0/movie_id").asText())
        .isEqualTo(contextual.toString());
    assertThat(request("/rankings/" + rank + "/entries", outsider.token(), Map.of()).status()).isEqualTo(404);
  }

  private HttpResponse<String> legacyMovieCreate(Account actor, long tmdb) throws Exception {
    var json=JSON.createObjectNode().put("portuguese_title","Synthetic").put("english_title","Synthetic")
        .put("director","Director").put("description","Description").put("runtime",90).put("tmdb_id",tmdb)
        .put("url_image","https://example.invalid/synthetic");
    json.putArray("genres");
    var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/movie"))
        .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+actor.token())
        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
    return http.send(post,HttpResponse.BodyHandlers.ofString());
  }

  @Test void contextualTmdbImportAuthorizesAndReusesWithoutLegacySideEffects() throws Exception {
    var owner = user(true);
    var outsider = user(false);
    UUID rank = ranking(owner, "PRIVATE");
    UUID otherRank = ranking(owner, "PRIVATE");
    assertThat(importTmdb(outsider, rank, 820001).statusCode()).isEqualTo(404);
    assertThat(TMDB_CALLS.get()).isZero();
    var imported = importTmdb(owner, rank, 820001);
    assertThat(imported.statusCode()).isEqualTo(201);
    assertThat(imported.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    var json = JSON.readTree(imported.body());
    assertThat(fields(json)).containsExactly("entry_id");
    assertThat(TMDB_CALLS.get()).isEqualTo(1);
    var same = importTmdb(owner, rank, 820001);
    assertThat(same.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(same.body())).isEqualTo(json);
    var other = importTmdb(owner, otherRank, 820001);
    assertThat(other.statusCode()).isEqualTo(201);
    assertThat(TMDB_CALLS.get()).isEqualTo(1);
    var catalog = jdbc.queryForMap("""
        select legacy_eligible, portuguese_title, english_title, director, description, original_title
        from movie where tmdb_id=820001
        """);
    assertThat(catalog).containsEntry("legacy_eligible",false).containsEntry("original_title","Synthetic KIT title")
        .containsEntry("portuguese_title",null).containsEntry("english_title",null)
        .containsEntry("director",null).containsEntry("description",null);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round",Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(*) from movie_note",Long.class)).isZero();
    assertThat(request("/movie?title=Synthetic%20KIT",owner.token(),Map.of()).status()).isEqualTo(204);
    assertThat(request("/rankings/"+rank+"/entries",outsider.token(),Map.of()).status()).isEqualTo(404);
  }

  @Test void tmdbProviderAndCanonicalIdentityFailuresRollBackAndKeepPrivateErrorCodes() throws Exception {
    var owner = user(false);
    UUID rank = ranking(owner, "PRIVATE");
    jdbc.update("""
        insert into movie(id,created_at,imdb_id,description,director,english_title,portuguese_title)
        values (?,now(),'tt900000','Unrelated canonical','Director','Other','Outro')
        """,UUID.randomUUID());
    for (String[] scenario : new String[][]{
        {"820003","502","ranking.tmdb_invalid_response"},
        {"820004","404","ranking.tmdb_not_found"},
        {"820005","503","ranking.tmdb_unavailable"},
        {"820006","409","ranking.catalog_identity_conflict"}}) {
      var response = importTmdb(owner,rank,Long.parseLong(scenario[0]));
      assertThat(response.statusCode()).isEqualTo(Integer.parseInt(scenario[1]));
      var error=JSON.readTree(response.body());
      assertThat(fields(error)).containsExactly("code");
      assertThat(error.required("code").asText()).isEqualTo(scenario[2]);
    }
    assertThat(TMDB_CALLS.get()).isEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from movie",Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round",Long.class)).isZero();
  }

  @Test void concurrentTmdbImportsAcrossRankingsDeduplicateCanonicalMovie() throws Exception {
    var owner = user(false);
    UUID a = ranking(owner, "PRIVATE"), b = ranking(owner, "PRIVATE");
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var one = executor.submit(() -> { start.await(); return importTmdb(owner,a,820002); });
      var two = executor.submit(() -> { start.await(); return importTmdb(owner,b,820002); });
      start.countDown();
      assertThat(one.get(15,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(201);
      assertThat(two.get(15,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(201);
    }
    assertThat(jdbc.queryForObject("select count(*) from movie where tmdb_id=820002",Long.class)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(distinct movie_id) from ranking_movie_entry",Long.class)).isEqualTo(1L);
    assertThat(TMDB_CALLS.get()).isBetween(1,2);
  }

  @Test void grantRevokedWhileKitRespondsAbortsTheContextualWrite() throws Exception {
    var owner = user(false);
    var member = user(false);
    UUID rank = ranking(owner, "PRIVATE");
    membership(rank, member, "ADD_ENTRY");
    var arrival = new java.util.concurrent.CountDownLatch(1);
    var resume = new java.util.concurrent.CountDownLatch(1);
    providerArrived=arrival;
    releaseProvider=resume;
    try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var call=executor.submit(() -> importTmdb(member,rank,820007));
      try {
        assertThat(arrival.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='ADD_ENTRY'",
            rank,member.id());
      } finally {
        resume.countDown();
      }
      var denied=call.get(10,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(denied.statusCode()).isEqualTo(404);
      assertThat(JSON.readTree(denied.body()).required("code").asText()).isEqualTo("ranking.not_found");
    } finally {
      providerArrived=null;
      releaseProvider=null;
    }
    assertThat(TMDB_CALLS.get()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from movie where tmdb_id=820007",Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isZero();
  }

  private HttpResponse<String> importTmdb(Account actor, UUID rank, long tmdb) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/entries/tmdb"))
        .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+actor.token()).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.createObjectNode().put("tmdb_id",tmdb).toString())).build();
    return http.send(request,HttpResponse.BodyHandlers.ofString());
  }

  @Test void authorizedDetailRevealsAtThirtyMinutesButListsAndAggregatesNeverAdvanceIt() throws Exception {
    var owner = user(false);
    var reader = user(false);
    var outsider = user(true);
    UUID rank = ranking(owner, "PRIVATE"), alternate = ranking(owner, "PRIVATE");
    membership(rank, reader, "READ");
    UUID entry=seedRevealEntry(rank), due=seedRound(rank,entry,1,false,1800),
        fresh=seedRound(rank,entry,2,false,60), unknown=seedRound(rank,entry,3,null,1900);
    UUID author=UUID.randomUUID();
    jdbc.update("insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",
        rank,author,owner.id(),UUID.randomUUID());
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,8,now())",
        rank,due,author);
    String entryPath="/rankings/"+rank+"/entries/"+entry;
    String rounds=entryPath+"/rounds";
    var initial=jdbc.queryForMap(
        "select created_at,updated_at,description from ranking_movie_round where ranking_id=? and round_id=?",rank,due);
    assertThat(request(rounds,reader.token(),Map.of()).body().at("/items/0/revealed").asBoolean()).isFalse();
    var subtotal=request(entryPath+"/ratings/aggregate",reader.token(),Map.of()).body();
    assertThat(subtotal.required("votes").asInt()).isZero();
    assertThat(subtotal.required("partial").asBoolean()).isTrue();
    assertThat(request(rounds+"/"+due,outsider.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings/"+alternate+"/entries/"+entry+"/rounds/"+due,owner.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request(rounds+"/bad-id",owner.token(),Map.of()).status()).isEqualTo(404);
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",
        Boolean.class,rank,due)).isFalse();

    var failBefore=request(rounds+"/"+fresh,reader.token(),Map.of());
    assertThat(failBefore.status()).isEqualTo(200);
    assertThat(failBefore.body().required("revealed").asBoolean()).isFalse();
    var nil=request(rounds+"/"+unknown,reader.token(),Map.of());
    assertThat(nil.status()).isEqualTo(200);
    assertThat(nil.body().required("revealed").isNull()).isTrue();
    try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var gate=new java.util.concurrent.CountDownLatch(1);
      var first=executor.submit(()->{gate.await();return request(rounds+"/"+due,reader.token(),Map.of());});
      var second=executor.submit(()->{gate.await();return request(rounds+"/"+due,owner.token(),Map.of());});
      gate.countDown();
      for(var future:List.of(first,second)) {
        var revealed=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(revealed.status()).isEqualTo(200);
        assertThat(revealed.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(fields(revealed.body())).containsExactlyInAnyOrder("round_id","round_number","revealed");
        assertThat(revealed.body().required("revealed").asBoolean()).isTrue();
      }
    }
    assertThat(jdbc.queryForMap("select created_at,updated_at,description from ranking_movie_round where ranking_id=? and round_id=?",
        rank,due)).isEqualTo(initial);
    assertThat(jdbc.queryForObject("select score from ranking_movie_rating where ranking_id=? and round_id=?",
        Integer.class,rank,due)).isEqualTo(8);
    var after=request(entryPath+"/ratings/aggregate",reader.token(),Map.of()).body();
    assertThat(after.required("votes").asInt()).isEqualTo(1);
    assertThat(after.required("total").asInt()).isEqualTo(8);
    assertThat(after.required("partial").asBoolean()).isTrue();
  }

  @Test void archivedOrRemovedOrNoLongerReadableDetailsDoNotReveal() throws Exception {
    var owner=user(false);
    var reader=user(false);
    UUID rank=ranking(owner,"PRIVATE");
    membership(rank, reader, "READ");
    UUID entry=seedRevealEntry(rank), round=seedRound(rank,entry,1,false,1900);
    String path="/rankings/"+rank+"/entries/"+entry+"/rounds/"+round;
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'",rank,reader.id());
    assertThat(request(path,reader.token(),Map.of()).status()).isEqualTo(404);
    jdbc.update("update ranking set archived_at=now() where id=?",rank);
    assertThat(request(path,owner.token(),Map.of()).body().required("revealed").asBoolean()).isFalse();
    jdbc.update("update ranking_movie_entry set removed_at=now(),removal_token=? where ranking_id=? and entry_id=?",
        UUID.randomUUID(),rank,entry);
    assertThat(request(path,owner.token(),Map.of()).status()).isEqualTo(404);
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",
        Boolean.class,rank,round)).isFalse();
  }

  private UUID seedRevealEntry(UUID rank) {
    UUID movie=UUID.randomUUID(), entry=UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic')
        """,movie);
    jdbc.update("insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at) values (?,?,?,now())",
        rank,entry,movie);
    return entry;
  }

  private UUID seedRound(UUID rank,UUID entry,long number,Boolean revealed,int ageSeconds) {
    UUID round=UUID.randomUUID();
    jdbc.update("""
        insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,revealed,created_at,updated_at,description)
        values (?,?,?,?,?,clock_timestamp()-make_interval(secs=>?),now(),'Synthetic round')
        """,rank,entry,round,number,revealed,ageSeconds);
    return round;
  }

  private String rotateInvitationAddress(Account actor) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/api/monolith/user/ranking-invitation-address/rotate"))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + actor.token());
    var response=http.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    var result=JSON.readTree(response.body());
    assertThat(fields(result)).containsExactly("recipient_code");
    String code=result.required("recipient_code").asText();
    assertThat(code).matches("[A-Za-z0-9_-]{21}[AQgw]");
    return code;
  }

  private void assertInvitationMissing(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).required("code").asText()).isEqualTo("ranking.invitation_not_found");
  }

  private HttpResponse<String> inviteRequest(Account actor, String method, UUID rank, String suffix, String target)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/api/monolith/rankings/" + rank + "/invitations" + suffix))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + actor.token());
    if (target != null) builder.header("Content-Type", "application/json");
    return http.send(builder.method(method, target == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JSON.createObjectNode().put("recipient_code", target).toString())).build(),
        HttpResponse.BodyHandlers.ofString());
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
