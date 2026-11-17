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
class ArchivedRankingHttpIT {
  private static final String SCHEMA = "ranking_archived_http_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test void internalArchivePreservesHistoryAndSameTokenSeesOnlyRead() throws Exception {
    var owner=user(false); var member=user(false); var noRead=user(false); var outsider=user(true);
    UUID rank=ranking(owner,"PUBLIC"); membership(rank,member,"READ","RATE_OWN","ADD_ENTRY"); membership(rank,noRead,"RATE_OWN");
    UUID movie=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)",movie,member.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,member.id());
    var history=historicalRows();
    assertThat(request("/rankings/"+rank+"/access",owner.token(),Map.of()).body().required("capabilities").size()).isEqualTo(RankingCapability.values().length);
    archiveFixture(rank);
    for(var reader:List.of(owner,member)) {
      var single=request("/rankings/"+rank+"/access",reader.token(),Map.of());
      assertThat(single.status()).isEqualTo(200);assertNoStore(single);
      assertThat(single.body().required("capabilities").size()).isEqualTo(1);
      assertThat(single.body().at("/capabilities/0").asText()).isEqualTo("READ");
      var list=request("/rankings/access",reader.token(),Map.of());
      assertThat(list.status()).isEqualTo(200);assertNoStore(list);
      assertThat(list.body().at("/items/0/capabilities/0").asText()).isEqualTo("READ");
      assertThat(list.body().at("/items/0/capabilities").size()).isEqualTo(1);
    }
    for(var denied:List.of(noRead,outsider)) {
      assertError(request("/rankings/"+rank+"/access",denied.token(),Map.of()),404,"ranking.not_found");
      assertThat(request("/rankings/access",denied.token(),Map.of()).body().required("items").size()).isZero();
    }
    assertThat(historicalRows()).isEqualTo(history);
    assertThat(jdbc.queryForObject("select visibility from "+table("ranking")+" where id=?",String.class,rank)).isEqualTo("PUBLIC");
    assertThat(grants(rank,member)).containsExactly("ADD_ENTRY","RATE_OWN","READ");
  }

  @Test void archivedOwnerCannotEditGrantsAndOutsidersCannotDiscoverArchive() throws Exception {
    var owner=user(false); var member=user(false); var outsider=user(true);
    UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ");archiveFixture(rank);
    var before=authorizationRows();
    assertError(put(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),409,"ranking.archived_read_only");
    assertError(put(rank,owner.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),409,"ranking.archived_read_only");
    for(var actor:List.of(member,outsider))
      assertError(put(rank,member.id(),actor.token(),"{\"capabilities\":[]}",Map.of()),404,"ranking.not_found");
    assertThat(authorizationRows()).isEqualTo(before);
  }

  @Test void archiveTimestampCannotBeResetOrChangedButIdenticalWriteIsStable() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");
    assertThat(jdbc.queryForObject("select archived_at from "+table("ranking")+" where id=?",java.time.OffsetDateTime.class,rank)).isNull();
    archiveFixture(rank);
    var timestamp=jdbc.queryForObject("select archived_at from "+table("ranking")+" where id=?",java.time.OffsetDateTime.class,rank);
    org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("update "+table("ranking")+" set archived_at=null where id=?",rank))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("update "+table("ranking")+" set archived_at=archived_at+interval '1 second' where id=?",rank))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update("update "+table("ranking")+" set archived_at=archived_at where id=?",rank);
    archiveFixture(rank);
    assertThat(jdbc.queryForObject("select archived_at from "+table("ranking")+" where id=?",java.time.OffsetDateTime.class,rank)).isEqualTo(timestamp);
  }

  @Test void grantsWaitingOnInternalArchiveRecheckReadOnlyStateAfterCommit() throws Exception {
    var owner=user(false);var member=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,member,"READ");
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try(var update=connection.prepareStatement("update "+table("ranking")+" set archived_at=current_timestamp where id=?")) {
        update.setObject(1,rank);update.executeUpdate();
        var pending=http.sendAsync(putRequest(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),HttpResponse.BodyHandlers.ofString());
        awaitRankingLock(connection);connection.commit();
        assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),409,"ranking.archived_read_only");
      }
    }
    assertThat(grants(rank,member)).containsExactly("READ");
  }

  @Test void grantsCommittedBeforeInternalArchiveRemainStoredButCannotGrantWriteAccess() throws Exception {
    var owner=user(false);var member=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,member,"READ");
    assertUpdated(put(rank,member.id(),owner.token(),"{\"capabilities\":[\"READ\",\"MANAGE_MEMBERS\"]}",Map.of()));
    archiveFixture(rank);
    assertThat(grants(rank,member)).containsExactly("MANAGE_MEMBERS","READ");
    var access=request("/rankings/"+rank+"/access",member.token(),Map.of());
    assertThat(access.body().required("capabilities").size()).isEqualTo(1);
    assertThat(access.body().at("/capabilities/0").asText()).isEqualTo("READ");
  }

  private void archiveFixture(UUID rank) {
    jdbc.update("update "+table("ranking")+" set archived_at=current_timestamp where id=? and archived_at is null",rank);
  }
  private List<String> historicalRows() {
    var result=new ArrayList<String>();
    for(String name:List.of("ranking_membership","ranking_membership_grant","movie_note","user_roles"))
      result.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));
    return result;
  }

  private void awaitRankingLock(java.sql.Connection blocker) throws Exception {
    int pid;
    try(var statement=blocker.createStatement();var result=statement.executeQuery("select pg_backend_pid()")){result.next();pid=result.getInt(1);}
    long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    while(System.nanoTime()<deadline) {
      if(Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from pg_stat_activity activity
          where activity.datname=current_database() and activity.wait_event_type='Lock'
            and ?=any(pg_blocking_pids(activity.pid))
            and activity.query like 'select owner_user_id, archived_at from ranking where id=%for update%'
            and exists(select 1 from pg_locks held where held.pid=activity.pid and held.relation=cast(? as regclass)))
          """,Boolean.class,pid,table("ranking")))) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Grant update did not reach PostgreSQL ranking lock barrier");
  }
  private List<String> grants(UUID rank,Account member) {
    return jdbc.queryForList("select capability from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? order by capability",String.class,rank,member.id());
  }
  private List<String> protectedRows() {
    var result=new ArrayList<String>();
    for(String name:List.of("ranking","ranking_membership","user_roles","movie_note"))
      result.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));
    return result;
  }
  private HttpRequest putRequest(UUID rank,UUID member,String token,String body,Map<String,String> headers) {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/members/"+member+"/capabilities"))
        .timeout(Duration.ofSeconds(10)).PUT(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type","application/json").header("Accept","application/json");
    if(token!=null)builder.header("Authorization","Bearer "+token);headers.forEach(builder::header);return builder.build();
  }
  private ApiResponse put(UUID rank,UUID member,String token,String body,Map<String,String> headers) throws Exception {
    return response(http.send(putRequest(rank,member,token,body,headers),HttpResponse.BodyHandlers.ofString()));
  }
  private void assertUpdated(ApiResponse response) {
    assertThat(response.status()).isEqualTo(204);assertThat(response.body().isNull()).isTrue();assertNoStore(response);
  }
  private void assertError(ApiResponse response,int status,String code) {
    assertThat(response.status()).isEqualTo(status);assertThat(fields(response.body())).containsExactly("code");
    assertThat(response.body().required("code").asText()).isEqualTo(code);assertNoStore(response);
  }
  private void assertNoStore(ApiResponse response) {
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
