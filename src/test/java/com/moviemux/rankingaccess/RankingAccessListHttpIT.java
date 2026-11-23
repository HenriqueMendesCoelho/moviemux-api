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
  private static final java.util.concurrent.atomic.AtomicReference<String> PICKER_QUERY = new java.util.concurrent.atomic.AtomicReference<>();
  private static final AtomicInteger PICKER_CALLS = new AtomicInteger();
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
    properties.add("ranking.rating-copy.preview-key",
        () -> java.util.Base64.getEncoder().encodeToString("A synthetic key for preview test".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    properties.add("ranking.rating-copy.preview-ttl-seconds", () -> "600");
    properties.add("ranking.catalog.tmdb-import-enabled", () -> "true");
    properties.add("ranking.creation.max-owned", () -> "1");
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
      if (path.equals("/api/v1/tmdb/search/movie")) {
        PICKER_CALLS.incrementAndGet();
        String raw=exchange.getRequestURI().getRawQuery();
        PICKER_QUERY.set(raw);
        var params=new java.util.HashMap<String,String>();
        for (String pair:raw.split("&")) {
          var fields=pair.split("=",2);
          params.put(fields[0],java.net.URLDecoder.decode(fields[1],java.nio.charset.StandardCharsets.UTF_8));
        }
        String q=params.get("query");
        int page=Integer.parseInt(params.get("page"));
        int status=q.equals("KIT delayed failure") ? 429 : 200;
        if(q.equals("KIT delayed failure")) {
          var arrived=providerArrived; var release=releaseProvider;
          if(arrived!=null && release!=null) {
            arrived.countDown();
            try { if(!release.await(6,java.util.concurrent.TimeUnit.SECONDS)) status=500; }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); status=500; }
          }
        }
        var payload=JSON.createObjectNode().put("page",page).put("total_pages",30).put("total_results",1500);
        var rows=payload.putArray("results");
        if(!q.equals("No results")) {
          rows.addObject().put("id",820010).put("title","Cinema traduzido").put("original_title","Synthetic Original")
              .put("release_date","2001-01-02").put("poster_path","/safe_movie.jpg")
              .put("vote_average",8.4).put("vote_count",300).put("overview","sensitive provider field");
          rows.addObject().put("id",820011).put("original_title","Fallback title").put("poster_path","/../unsafe.jpg")
              .put("popularity",100).put("adult",false);
        }
        byte[] bytes=(status==200?payload.toString():"synthetic-private-upstream-search-error")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return;
      }
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
    PICKER_CALLS.set(0);
    PICKER_QUERY.set(null);
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
    // An archived collection still counts even when the compatibility flag is entirely absent.
    var afterArchive=response(http.send(createRequest(owner,json),HttpResponse.BodyHandlers.ofString()));
    assertThat(afterArchive.status()).isEqualTo(409);
    assertThat(afterArchive.body().required("code").asText()).isEqualTo("ranking.creation_limit_reached");
    assertThat(afterArchive.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(jdbc.queryForList("select id from ranking where owner_user_id=?",UUID.class,owner.id())).containsExactly(id);
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
    var common=(com.fasterxml.jackson.databind.node.ObjectNode)detail.body().deepCopy();
    var proof=common.remove("can_share_open_link");
    assertThat(proof.isBoolean()).isTrue();
    assertThat(proof.asBoolean()).isFalse();
    assertThat(common).isEqualTo(request("/rankings", member.token(), Map.of()).body().required("items").get(0));
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

  @Test void openLinkProofIsOnlyForCurrentLiveOwnerEvenWithFullyDelegatedGrants() throws Exception {
    var owner=user(false);
    var delegated=user(false);
    var admin=user(true);
    UUID rank=ranking(owner,"PUBLIC");
    membership(rank,delegated,java.util.Arrays.stream(RankingCapability.values()).map(Enum::name).toArray(String[]::new));
    grant(rank,owner,"READ");
    String path="/rankings/"+rank;

    assertThat(request(path,owner.token(),Map.of()).body().required("can_share_open_link").asBoolean()).isFalse();
    jdbc.update("update ranking set admission='OPEN' where id=?",rank);
    var eligible=request(path,owner.token(),Map.of());
    assertThat(eligible.status()).isEqualTo(200);
    assertThat(eligible.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(fields(eligible.body())).containsExactlyInAnyOrder("ranking_id","name","visibility","admission",
        "capabilities","can_share_open_link");
    assertThat(eligible.body().required("can_share_open_link").isBoolean()).isTrue();
    assertThat(eligible.body().required("can_share_open_link").asBoolean()).isTrue();

    var delegatedScope=request(path,delegated.token(),Map.of());
    assertThat(delegatedScope.body().required("capabilities").size()).isEqualTo(RankingCapability.values().length);
    assertThat(delegatedScope.body().required("can_share_open_link").asBoolean()).isFalse();
    assertThat(request(path,admin.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings",owner.token(),Map.of()).body().required("items").get(0).has("can_share_open_link")).isFalse();
    assertThat(request("/public/rankings/"+rank,null,Map.of()).body().has("can_share_open_link")).isFalse();

    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?",rank,delegated.id());
    assertThat(request(path,delegated.token(),Map.of()).status()).isEqualTo(404);
    jdbc.update("update ranking_membership set active=true where ranking_id=? and user_id=?",rank,delegated.id());
    // Apply a synthetic owner-state change directly to show that the next read uses current ownership.
    // No ownership-transfer API or product transition is introduced by this fixture.
    jdbc.update("update ranking set owner_user_id=? where id=?",delegated.id(),rank);
    assertThat(request(path,owner.token(),Map.of()).body().required("can_share_open_link").asBoolean()).isFalse();
    assertThat(request(path,delegated.token(),Map.of()).body().required("can_share_open_link").asBoolean()).isTrue();
    jdbc.update("update ranking set archived_at=now() where id=?",rank);
    var archived=request(path,delegated.token(),Map.of()).body();
    assertThat(archived.required("can_share_open_link").asBoolean()).isFalse();
    assertThat(archived.required("capabilities").size()).isEqualTo(1);
    assertThat(archived.at("/capabilities/0").asText()).isEqualTo("READ");
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

  @Test void tmdbPickerEncodesEachParameterAndProjectsNoVotesOrWrites() throws Exception {
    var owner=user(false);
    var outsider=user(true);
    UUID rank=ranking(owner,"PRIVATE");
    assertThat(picker(outsider,rank,"Cinema & ação + #",1,"pt-BR").statusCode()).isEqualTo(404);
    assertThat(picker(owner,rank,"X",1,"pt-BR").statusCode()).isEqualTo(400);
    assertThat(picker(owner,rank,"Cinema",1,"en").statusCode()).isEqualTo(400);
    assertThat(PICKER_CALLS.get()).isZero();
    var response=picker(owner,rank,"  Cinema & ação + #  ",1,"pt-BR");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));
    String raw=PICKER_QUERY.get();
    assertThat(raw.split("&")).hasSize(4);
    assertThat(raw).contains("query=Cinema%20%26%20a%C3%A7%C3%A3o%20%2B%20%23","page=1","language=pt-BR","include_adult=false")
        .doesNotContain(rank.toString(),owner.id().toString());
    var body=JSON.readTree(response.body());
    assertThat(fields(body)).containsExactlyInAnyOrder("items","page","has_more");
    assertThat(body.required("has_more").asBoolean()).isTrue();
    for(var item:body.required("items")) assertThat(fields(item)).containsExactlyInAnyOrder(
        "tmdb_id","title","original_title","release_date","poster_path");
    assertThat(body.at("/items/0/poster_path").asText()).isEqualTo("/safe_movie.jpg");
    assertThat(body.at("/items/1/title").asText()).isEqualTo("Fallback title");
    assertThat(body.at("/items/1/poster_path").isNull()).isTrue();
    assertThat(JSON.readTree(picker(owner,rank,"Cinema",20,"en-US").body()).required("has_more").asBoolean()).isFalse();
    var empty=JSON.readTree(picker(owner,rank,"No results",2,"en-US").body());
    assertThat(empty.required("items").size()).isZero();
    assertThat(empty.required("has_more").asBoolean()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from movie",Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void pickerRevocationDuringProviderErrorWinsOverUpstreamOutcome(boolean blockAccount) throws Exception {
    var owner=user(false);
    var member=user(false);
    UUID rank=ranking(owner,"PRIVATE");
    membership(rank,member,"ADD_ENTRY");
    var arrived=new java.util.concurrent.CountDownLatch(1);
    var resume=new java.util.concurrent.CountDownLatch(1);
    providerArrived=arrived;
    releaseProvider=resume;
    try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var call=executor.submit(()->picker(member,rank,"KIT delayed failure",1,"en-US"));
      try {
        assertThat(arrived.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        if (blockAccount)
          jdbc.update("update user_statistics set consecutive_failed_login_attempts=10 where user_id=?",member.id());
        else
          jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='ADD_ENTRY'",
              rank,member.id());
      } finally { resume.countDown(); }
      var denied=call.get(15,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(denied.statusCode()).isEqualTo(404);
      assertThat(JSON.readTree(denied.body()).required("code").asText()).isEqualTo("ranking.not_found");
    } finally {
      providerArrived=null;
      releaseProvider=null;
    }
    assertThat(PICKER_CALLS.get()).isEqualTo(1);
  }

  private HttpResponse<String> picker(Account actor,UUID rank,String query,int page,String language) throws Exception {
    var json=JSON.createObjectNode().put("query",query).put("page",page).put("language",language);
    var post=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/entries/tmdb/search"))
        .timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+actor.token()).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.toString())).build();
    return http.send(post,HttpResponse.BodyHandlers.ofString());
  }

  @Test void perRoundAggregateNeverIncludesHiddenParticipationAndPreservesNumericHistory() throws Exception {
    var owner=user(false);
    var reader=user(false);
    var outsider=user(true);
    UUID rank=ranking(owner,"PRIVATE"), second=ranking(owner,"PRIVATE");
    membership(rank,reader,"READ");
    UUID entry=seedRevealEntry(rank), shown=seedRound(rank,entry,1,true,3600),
        hidden=seedRound(rank,entry,2,false,3600), unknown=seedRound(rank,entry,3,null,3600),
        noVotes=seedRound(rank,entry,4,true,3600);
    UUID first=seedAuthor(rank,owner.id()), own=seedAuthor(rank,reader.id()), anonymized=seedAuthor(rank,null);
    for (var vote : java.util.Arrays.asList(new Object[]{rank,shown,first,-12},new Object[]{rank,shown,own,0},
        new Object[]{rank,shown,anonymized,null},new Object[]{rank,hidden,own,300})) {
      jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",vote);
    }
    String base="/rankings/"+rank+"/entries/"+entry+"/rounds/";
    String suffix="/ratings/aggregate";
    var detailed=request(base+shown+suffix,reader.token(),Map.of());
    assertThat(detailed.status()).isEqualTo(200);
    assertThat(detailed.headers().firstValue("Cache-Control")).hasValueSatisfying(value->assertThat(value).contains("no-store"));
    assertThat(fields(detailed.body())).containsExactlyInAnyOrder("round_id","revealed","total","votes");
    assertThat(detailed.body().required("revealed").asBoolean()).isTrue();
    assertThat(detailed.body().required("total").asInt()).isEqualTo(-12);
    assertThat(detailed.body().required("votes").asInt()).isEqualTo(2);
    var blank=request(base+noVotes+suffix,reader.token(),Map.of()).body();
    assertThat(blank.required("total").asInt()).isZero();
    assertThat(blank.required("votes").asInt()).isZero();
    var hiddenView=request(base+hidden+suffix,reader.token(),Map.of()).body();
    assertThat(hiddenView.required("revealed").asBoolean()).isFalse();
    assertThat(hiddenView.required("total").isNull()).isTrue();
    assertThat(hiddenView.required("votes").isNull()).isTrue();
    jdbc.update("update ranking_movie_rating set score=-999 where ranking_id=? and round_id=? and author_id=?",rank,hidden,own);
    assertThat(request(base+hidden+suffix,reader.token(),Map.of()).body()).isEqualTo(hiddenView);
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",
        Boolean.class,rank,hidden)).isFalse();
    var uncertain=request(base+unknown+suffix,reader.token(),Map.of()).body();
    assertThat(uncertain.required("revealed").isNull()).isTrue();
    assertThat(uncertain.required("total").isNull()).isTrue();
    assertThat(uncertain.required("votes").isNull()).isTrue();
    assertThat(request(base+shown+suffix,outsider.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings/"+second+"/entries/"+entry+"/rounds/"+shown+suffix,
        owner.token(),Map.of()).status()).isEqualTo(404);
    jdbc.update("update ranking set archived_at=now() where id=?",rank);
    assertThat(request(base+shown+suffix,reader.token(),Map.of()).body()).isEqualTo(detailed.body());
  }

  @Test void openJoinIsExplicitScopedAtomicAndNeverReinstatesOrRewritesGrants() throws Exception {
    var owner=user(false);
    var joiner=user(false);
    var other=user(true);
    UUID open=ranking(owner,"PRIVATE"), closed=ranking(owner,"PUBLIC"), approval=ranking(owner,"PUBLIC"),
        archived=ranking(owner,"PUBLIC"), publicOpen=ranking(owner,"PUBLIC");
    jdbc.update("update ranking set admission='OPEN' where id in (?,?,?)",open,archived,publicOpen);
    jdbc.update("update ranking set archived_at=now() where id=?",archived);
    jdbc.update("update ranking set admission='APPROVAL' where id=?",approval);
    String scope="/rankings/"+open;
    assertThat(request(scope,joiner.token(),Map.of()).status()).isEqualTo(404);
    assertEmpty(list(joiner,""));

    var denied = joinOpen(joiner,UUID.randomUUID().toString(),"",null);
    assertThat(denied.status()).isEqualTo(404);
    assertThat(denied.body().required("code").asText()).isEqualTo("ranking.not_found");
    for (String id : List.of("not-an-id",closed.toString(),approval.toString(),archived.toString())) {
      var outside=joinOpen(joiner,id,"",null);
      assertThat(outside.status()).isEqualTo(denied.status());
      assertThat(outside.body()).isEqualTo(denied.body());
      assertThat(outside.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
    for (var invalid : List.of(joinOpen(joiner,open.toString(),"","{}"),
         joinOpen(joiner,open.toString(),"?user_id="+other.id(),null))) {
      assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().required("code").asText()).isEqualTo("ranking.invalid_join");
      assertThat(invalid.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
    assertThat(joinOpen(null,open.toString(),"",null).status()).isIn(401,403);
    assertEmpty(list(joiner,""));

    long entries=jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class);
    long invites=jdbc.queryForObject("select count(*) from ranking_invitation",Long.class);
    // Both requests race the one membership creation. Neither rewrites the grants after it exists.
    try (var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var ready=new java.util.concurrent.CountDownLatch(2);
      var go=new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<ApiResponse> attempt=()->{
        ready.countDown();
        if (!go.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("race not started");
        return joinOpen(joiner,open.toString(),"",null);
      };
      var first=pool.submit(attempt);
      var second=pool.submit(attempt);
      try {
        assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      } finally { go.countDown(); }
      for (var joined : List.of(first.get(12,java.util.concurrent.TimeUnit.SECONDS),
           second.get(12,java.util.concurrent.TimeUnit.SECONDS))) {
        assertThat(joined.status()).isEqualTo(204);
        assertThat(joined.body().isNull()).isTrue();
        assertThat(joined.headers().firstValue("Cache-Control")).hasValue("no-store");
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership where ranking_id=? and user_id=?",
        Integer.class,open,joiner.id())).isEqualTo(1);
    assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?",
        String.class,open,joiner.id())).containsExactlyInAnyOrder("READ","RATE_OWN");
    assertThat(request(scope,joiner.token(),Map.of()).status()).isEqualTo(200);
    assertThat(list(joiner,"").body().required("items").get(0).required("ranking_id").asText()).isEqualTo(open.toString());
    assertThat(request(scope,other.token(),Map.of()).status()).isEqualTo(404);

    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='RATE_OWN'",
        open,joiner.id());
    assertThat(joinOpen(joiner,open.toString(),"",null).status()).isEqualTo(204);
    assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?",
        String.class,open,joiner.id())).containsExactly("READ");
    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?",open,joiner.id());
    assertThat(joinOpen(joiner,open.toString(),"",null).body()).isEqualTo(denied.body());
    assertThat(joinOpen(joiner,publicOpen.toString(),"",null).status()).isEqualTo(204);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Long.class)).isEqualTo(entries);
    assertThat(jdbc.queryForObject("select count(*) from ranking_invitation",Long.class)).isEqualTo(invites);
  }

  private ApiResponse joinOpen(Account actor,String rank,String query,String body) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/join"+query))
        .timeout(Duration.ofSeconds(10));
    if (actor!=null) builder.header("Authorization","Bearer "+actor.token());
    if (body!=null) builder.header("Content-Type","application/json");
    return response(http.send(builder.POST(body==null?HttpRequest.BodyPublishers.noBody():
        HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()));
  }

  @Test void ownRatingCopyPreviewUsesExactContextsAndNeverChangesEitherRanking() throws Exception {
    var owner=user(false);
    var actor=user(false);
    var outsider=user(true);
    UUID sourceRank=ranking(owner,"PRIVATE"),targetRank=ranking(owner,"PRIVATE");
    membership(sourceRank,actor,"READ");
    membership(targetRank,actor,"READ","RATE_OWN");
    UUID sourceEntry=seedRevealEntry(sourceRank),targetEntry=UUID.randomUUID(),wrongEntry=seedRevealEntry(targetRank);
    jdbc.update("""
        insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at)
        select ?,?,movie_id,now() from ranking_movie_entry where ranking_id=? and entry_id=?
        """,targetRank,targetEntry,sourceRank,sourceEntry);
    UUID sourceRound=seedRound(sourceRank,sourceEntry,3,false,4000),
        targetRound=seedRound(targetRank,targetEntry,8,true,5000),
        wrongRound=seedRound(targetRank,wrongEntry,3,true,5000);
    UUID sourceAuthor=seedAuthor(sourceRank,actor.id());
    UUID sourcePeer=seedAuthor(sourceRank,owner.id()),targetPeer=seedAuthor(targetRank,owner.id());
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",
        sourceRank,sourceRound,sourcePeer,10);
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",
        targetRank,targetRound,targetPeer,9);

    var source=JSON.createObjectNode().put("ranking_id",sourceRank.toString()).put("entry_id",sourceEntry.toString())
        .put("round_id",sourceRound.toString());
    var body=JSON.createObjectNode().set("source",source);
    String base="/rankings/"+targetRank+"/entries/"+targetEntry+"/rounds/"+targetRound+"/rating/me";
    String path=base+"/copy-preview";
    assertCopyBlocked(copyPreview(actor,path,body), "SOURCE_INELIGIBLE");

    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",
        sourceRank,sourceRound,sourceAuthor,0);
    var before=copyAffectedRows();
    var result=copyPreview(actor,path,body);
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(fields(result.body())).containsExactlyInAnyOrder("status","source_score","preview_token","expires_at");
    assertThat(result.body().required("status").asText()).isEqualTo("READY");
    assertThat(result.body().required("source_score").isIntegralNumber()).isTrue();
    assertThat(result.body().required("source_score").asInt()).isZero();
    String token=result.body().required("preview_token").asText();
    assertThat(token).matches("[A-Za-z0-9_-]+");
    assertThat(java.time.Instant.parse(result.body().required("expires_at").asText())).isAfter(java.time.Instant.now());
    assertThat(copyPreview(actor,path,body).body().required("preview_token").asText()).isNotEqualTo(token);
    // The repository's application-wide unmatched-path handler may map framework 404 to 500.
    // No secret is sent to the intentionally unmapped route; the controller test proves no mapping.
    assertThat(copyPreview(actor,base+"/copy-apply",JSON.createObjectNode()).status()).isGreaterThanOrEqualTo(400);
    assertThat(copyAffectedRows()).isEqualTo(before);

    String code=copyPreview(outsider,path,body).body().required("code").asText();
    assertThat(code).isEqualTo("ranking.not_found");
    for (String outside : List.of(
         "/rankings/"+targetRank+"/entries/"+wrongEntry+"/rounds/"+wrongRound+"/rating/me/copy-preview",
         "/rankings/"+targetRank+"/entries/"+targetEntry+"/rounds/"+sourceRound+"/rating/me/copy-preview")) {
      var denied=copyPreview(actor,outside,body);
      assertThat(denied.status()).isEqualTo(404);
      assertThat(denied.headers().firstValue("Cache-Control")).hasValue("no-store");
      assertThat(fields(denied.body())).containsExactly("code");
      assertThat(denied.body().required("code").asText()).isEqualTo(code);
    }
    var invalid=JSON.createObjectNode().set("source",source.deepCopy().put("score",8));
    assertThat(copyPreview(actor,path,invalid).status()).isEqualTo(400);
    assertThat(copyPreview(actor,path+"?score=8",body).status()).isEqualTo(400);

    jdbc.update("update ranking_movie_rating set score=? where ranking_id=? and round_id=? and author_id=?",11,
        sourceRank,sourceRound,sourceAuthor);
    assertCopyBlocked(copyPreview(actor,path,body), "SOURCE_INELIGIBLE");
    jdbc.update("update ranking_movie_rating set score=? where ranking_id=? and round_id=? and author_id=?",null,
        sourceRank,sourceRound,sourceAuthor);
    assertCopyBlocked(copyPreview(actor,path,body), "SOURCE_INELIGIBLE");
    jdbc.update("update ranking_movie_rating set score=10 where ranking_id=? and round_id=? and author_id=?",
        sourceRank,sourceRound,sourceAuthor);

    UUID targetAuthor=seedAuthor(targetRank,actor.id());
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,null,now())",
        targetRank,targetRound,targetAuthor);
    assertCopyBlocked(copyPreview(actor,path,body), "DESTINATION_EXISTS");
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'",targetRank,actor.id());
    assertThat(copyPreview(actor,path,body).body().required("code").asText()).isEqualTo(code);
    grant(targetRank,actor,"READ");
    jdbc.update("update ranking set archived_at=now() where id=?",sourceRank);
    assertThat(copyPreview(actor,path,body).body().required("code").asText()).isEqualTo(code);
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",
        Boolean.class,sourceRank,sourceRound)).isFalse();
    assertThat(TMDB_CALLS.get()).isZero();
    assertThat(PICKER_CALLS.get()).isZero();
  }

  private List<String> copyAffectedRows() {
    var existing=new ArrayList<>(authorizationRows());
    for (String name : List.of("ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating")) {
      existing.addAll(jdbc.queryForList("select row_to_json(t)::text from " + table(name)
          + " t order by row_to_json(t)::text", String.class));
    }
    return existing;
  }

  private ApiResponse copyPreview(Account actor,String path,JsonNode body) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+actor.token())
        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    return response(http.send(request.build(),HttpResponse.BodyHandlers.ofString()));
  }

  private void assertCopyBlocked(ApiResponse response,String code) {
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(response.body().required("status").asText()).isEqualTo(code);
    assertThat(response.body().required("source_score").isNull()).isTrue();
    assertThat(response.body().required("preview_token").isNull()).isTrue();
    assertThat(response.body().required("expires_at").isNull()).isTrue();
  }

  @Test void anonymousPublicProjectionPagesOnlyDisclosedValuesWithoutHiddenSignals() throws Exception {
    var owner=user(false);
    var signedVisitor=user(true);
    UUID rank=ranking(owner,"PUBLIC"), separate=ranking(owner,"PUBLIC");
    UUID shownEntry=seedRevealEntry(rank), noTally=seedRevealEntry(rank), removed=seedRevealEntry(rank),
        foreignEntry=seedRevealEntry(separate);
    jdbc.update("""
        update movie set original_title='Original public',english_title='EN public',portuguese_title='PT public'
        where id=(select movie_id from ranking_movie_entry where ranking_id=? and entry_id=?)
        """,rank,shownEntry);
    jdbc.update("""
        update movie set original_title=?,english_title='EN fallback',portuguese_title='PT fallback'
        where id=(select movie_id from ranking_movie_entry where ranking_id=? and entry_id=?)
        """," ",rank,noTally);
    jdbc.update("update ranking_movie_entry set removed_at=now(),removal_token=? where ranking_id=? and entry_id=?",
        UUID.randomUUID(),rank,removed);
    UUID shown=seedRound(rank,shownEntry,1,true,3600), overdue=seedRound(rank,shownEntry,2,false,3600),
        unknown=seedRound(rank,shownEntry,3,null,3600), hiddenOnly=seedRound(rank,noTally,1,false,3600),
        unrelated=seedRound(separate,foreignEntry,1,true,3600);
    UUID actor=seedAuthor(rank,owner.id()), anonymized=seedAuthor(rank,null), legacyNull=seedAuthor(rank,null),
        foreign=seedAuthor(separate,owner.id());
    for (var row : java.util.Arrays.asList(new Object[]{rank,shown,actor,0},new Object[]{rank,shown,anonymized,-8},
        new Object[]{rank,shown,legacyNull,null},new Object[]{rank,overdue,actor,10},
        new Object[]{rank,unknown,actor,10},new Object[]{separate,unrelated,foreign,10})) {
      jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",row);
    }
    String path="/public/rankings/"+rank;
    for(String mode:List.of("INVITATION","OPEN","APPROVAL")) {
      jdbc.update("update ranking set admission=? where id=?",mode,rank);
      var summary=request(path,null,Map.of());
      assertThat(summary.status()).isEqualTo(200);
      assertThat(summary.headers().firstValue("Cache-Control")).hasValue("no-store");
      assertThat(fields(summary.body())).containsExactlyInAnyOrder("ranking_id","ranking_name");
      assertThat(summary.body().required("ranking_id").asText()).isEqualTo(rank.toString());
      assertThat(summary.body().required("ranking_name").isNull()).isTrue();
      assertThat(request(path,signedVisitor.token(),Map.of()).body()).isEqualTo(summary.body());
      var noisy=request(path,null,Map.of("Authorization","Bearer intentionally-invalid-public-visitor-token",
          "Cookie","JSESSIONID=obsolete","X-User-Id",signedVisitor.id().toString(),"Accept-Language","pt-BR"));
      assertThat(noisy.status()).isEqualTo(200);
      assertThat(noisy.body()).isEqualTo(summary.body());
      assertThat(noisy.headers().allValues("Set-Cookie")).isEmpty();
    }
    jdbc.update("update ranking set name='Synthetic public name' where id=?",rank);
    assertThat(request(path,null,Map.of()).body().required("ranking_name").asText()).isEqualTo("Synthetic public name");

    var catalogue=request(path+"/entries",null,Map.of());
    assertThat(catalogue.status()).isEqualTo(200);
    assertThat(catalogue.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(fields(catalogue.body())).containsExactlyInAnyOrder("items","next_cursor");
    assertThat(catalogue.body().required("next_cursor").isNull()).isTrue();
    var publicRows=new java.util.LinkedHashMap<String,JsonNode>();
    for (var row:catalogue.body().required("items")) {
      assertThat(fields(row)).containsExactlyInAnyOrder("entry_id","title","total","votes");
      publicRows.put(row.required("entry_id").asText(),row);
    }
    assertThat(new ArrayList<>(publicRows.keySet()))
        .containsExactlyElementsOf(java.util.stream.Stream.of(shownEntry.toString(),noTally.toString()).sorted().toList());
    assertThat(publicRows.get(shownEntry.toString()).required("title").asText()).isEqualTo("Original public");
    assertThat(publicRows.get(shownEntry.toString()).required("total").asInt()).isEqualTo(-8);
    assertThat(publicRows.get(shownEntry.toString()).required("votes").asInt()).isEqualTo(2);
    assertThat(publicRows.get(noTally.toString()).required("title").asText()).isEqualTo("EN fallback");
    assertThat(publicRows.get(noTally.toString()).required("total").asInt()).isZero();
    assertThat(publicRows.get(noTally.toString()).required("votes").asInt()).isZero();

    jdbc.update("update ranking_movie_rating set score=235 where ranking_id=? and round_id in (?,?)",rank,overdue,unknown);
    jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",
        rank,hiddenOnly,actor,3);
    assertThat(request(path+"/entries",null,Map.of()).body()).isEqualTo(catalogue.body());
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=? and round_id=?",
        Boolean.class,rank,overdue)).isFalse();
    var head=request(path+"/entries?limit=1",null,Map.of()).body();
    String cursor=head.required("next_cursor").asText();
    assertThat(cursor).isEqualTo(new ArrayList<>(publicRows.keySet()).getFirst());
    var tail=request(path+"/entries?after="+cursor+"&limit=1",null,Map.of()).body();
    assertThat(tail.required("items").get(0).required("entry_id").asText()).isNotEqualTo(cursor);
    assertThat(tail.required("next_cursor").isNull()).isTrue();
    assertThat(request(path+"/entries?after=ffffffff-ffff-ffff-ffff-ffffffffffff",null,Map.of()).body()
        .required("items").size()).isZero();

    assertThat(request("/rankings/"+rank,signedVisitor.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings/"+rank+"/access",null,Map.of()).status()).isIn(401,403);
    assertThat(request(path+"/entries?limit=51",null,Map.of()).status()).isEqualTo(400);
    var generic=request("/public/rankings/"+UUID.randomUUID(),null,Map.of());
    assertThat(generic.status()).isEqualTo(404);
    assertThat(generic.body().required("code").asText()).isEqualTo("ranking.not_found");
    jdbc.update("update ranking set visibility='PRIVATE' where id=?",rank);
    for(String suffix:List.of("","/entries","/entries?limit=51")) {
      var withheld=request(path+suffix,null,Map.of());
      assertThat(withheld.status()).isEqualTo(404);
      assertThat(withheld.body()).isEqualTo(generic.body());
      assertThat(withheld.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
    jdbc.update("update ranking set visibility='PUBLIC',archived_at=now() where id=?",rank);
    assertThat(request(path,null,Map.of()).body()).isEqualTo(generic.body());
    assertThat(request(path+"/entries",null,Map.of()).body()).isEqualTo(generic.body());
    assertThat(request("/public/rankings/not-a-uuid/entries?limit=51",null,Map.of()).body()).isEqualTo(generic.body());
  }

  @Test void anonymousRankingNamespaceOnlyAllowsExactPublicReadsNothingElse() throws Exception {
    UUID rank=ranking(user(false),"PUBLIC");
    var baseline=copyAffectedRows();
    String path="/public/rankings/"+rank;
    for (String suffix:List.of("", "/entries")) {
      for (String method:List.of("HEAD","OPTIONS","POST","PUT","PATCH","DELETE")) {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path+suffix))
            .timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody()).build();
        var rejected=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(404);
        assertThat(rejected.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));
        if (method.equals("HEAD")) assertThat(rejected.body()).isEmpty();
        else assertThat(JSON.readTree(rejected.body()).required("code").asText()).isEqualTo("ranking.not_found");
      }
    }
    for(var alias:List.of(request("/public/rankings",null,Map.of()),request(path+"/rating",null,Map.of()))) {
      assertThat(alias.status()).isEqualTo(404);
      assertThat(alias.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));
      assertThat(alias.body().required("code").asText()).isEqualTo("ranking.not_found");
    }
    var preflight=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .header("Origin","https://synthetic.example.test").header("Access-Control-Request-Method","GET")
        .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build();
    assertThat(http.send(preflight,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    assertThat(request(path+"/entries",null,Map.of()).body().required("items").size()).isZero();
    assertThat(copyAffectedRows()).isEqualTo(baseline);
  }

  private UUID seedAuthor(UUID rank,UUID account) {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",
        rank,id,account,UUID.randomUUID());
    return id;
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
