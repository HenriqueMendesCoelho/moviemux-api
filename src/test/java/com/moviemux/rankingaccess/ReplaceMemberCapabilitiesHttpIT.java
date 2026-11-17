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
class ReplaceMemberCapabilitiesHttpIT {
  private static final String SCHEMA = "ranking_grants_http_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test void ownerReplacesWholeSetNormalizesDuplicatesAndRevokesSameTokenAccess() throws Exception {
    var owner=user(false); var member=user(false); var other=user(false);
    UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ","RATE_OWN"); membership(rank,other,"READ");
    UUID movie=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?)",movie,member.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,member.id());
    var untouched = protectedRows();
    assertThat(request("/rankings/"+rank+"/access",member.token(),Map.of()).status()).isEqualTo(200);
    assertUpdated(put(rank,member.id(),owner.token(),"{\"capabilities\":[\"READ\",\"ADD_ENTRY\",\"READ\"]}",Map.of()));
    assertThat(grants(rank,member)).containsExactly("ADD_ENTRY","READ");
    var after=authorizationRows();
    assertUpdated(put(rank,member.id(),owner.token(),"{\"capabilities\":[\"READ\",\"ADD_ENTRY\"]}",Map.of()));
    assertThat(authorizationRows()).isEqualTo(after);
    assertUpdated(put(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()));
    assertThat(grants(rank,member)).isEmpty();
    assertThat(request("/rankings/"+rank+"/access",member.token(),Map.of()).status()).isEqualTo(404);
    assertThat(request("/rankings/access",member.token(),Map.of()).body().required("items").size()).isZero();
    assertThat(grants(rank,other)).containsExactly("READ");
    assertThat(protectedRows()).isEqualTo(untouched);
  }

  @Test void onlyCurrentOwnerMayEditActiveNonOwnerAndAllInaccessibleCasesAreGeneric() throws Exception {
    var owner=user(false); var member=user(false); var manager=user(false); var admin=user(true);
    UUID rank=ranking(owner,"PRIVATE"); UUID foreign=ranking(admin,"PRIVATE");
    membership(rank,member,"READ"); membership(rank,manager,"READ","MANAGE_MEMBERS");
    var before=authorizationRows();
    var forged=Map.of("X-User-Id",owner.id().toString(),"X-Role","ROLE_ADM");
    for (var actor:List.of(member,manager,admin)) assertError(put(rank,member.id(),actor.token(),"{\"capabilities\":[]}",forged),404,"ranking.not_found");
    assertError(put(foreign,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),404,"ranking.not_found");
    assertError(put(rank,UUID.randomUUID(),owner.token(),"{\"capabilities\":[]}",Map.of()),404,"ranking.not_found");
    assertError(put(UUID.randomUUID(),member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),404,"ranking.not_found");
    assertError(put(rank,owner.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),409,"ranking.owner_capabilities_immutable");
    assertThat(authorizationRows()).isEqualTo(before);
    jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,member.id());
    before=authorizationRows();
    assertError(put(rank,member.id(),owner.token(),"{\"capabilities\":[\"READ\"]}",Map.of()),404,"ranking.not_found");
    assertThat(authorizationRows()).isEqualTo(before);
    assertThat(put(rank,member.id(),null,"{\"capabilities\":[]}",Map.of()).status()).isIn(401,403);
  }

  @Test void malformedBodiesNeverPartiallyReplaceGrants() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ","RATE_OWN");
    var before=authorizationRows();
    for(String body:List.of("{}","null","{\"capabilities\":null}","{\"capabilities\":[null]}",
        "{\"capabilities\":[\"READ\",\"ADMIN\"]}","{\"capabilities\":[true]}","{\"capabilities\":[1]}",
        "{\"capabilities\":[{}]}","{\"capabilities\":\"READ\"}","{")) {
      assertError(put(rank,member.id(),owner.token(),body,Map.of()),400,"ranking.invalid_capabilities");
      assertThat(authorizationRows()).isEqualTo(before);
    }
  }

  @Test void redundantPayloadBoundIsCheckedBeforeDuplicateNormalization() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ","RATE_OWN");
    var before=authorizationRows();
    String oversized=JSON.writeValueAsString(Map.of("capabilities",java.util.Collections.nCopies(101,"READ")));
    assertError(put(rank,member.id(),owner.token(),oversized,Map.of()),400,"ranking.invalid_capabilities");
    assertThat(authorizationRows()).isEqualTo(before);
    String maximum=JSON.writeValueAsString(Map.of("capabilities",java.util.Collections.nCopies(100,"READ")));
    assertUpdated(put(rank,member.id(),owner.token(),maximum,Map.of()));
    assertThat(grants(rank,member)).containsExactly("READ");
  }

  @Test void failedInsertRollsBackDeletionAndAnyPreviousInsertion() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ");
    var before=authorizationRows();
    // Fail on the second insert regardless of Set iteration order, after DELETE and one successful insert.
    jdbc.execute("create function "+table("reject_test_grant")+"() returns trigger language plpgsql as $$ begin if exists (select 1 from "
        +table("ranking_membership_grant")+" existing where existing.ranking_id=NEW.ranking_id and existing.user_id=NEW.user_id) then raise exception 'synthetic second insert failure'; end if; return NEW; end $$");
    jdbc.execute("create trigger reject_test_grant before insert on "+table("ranking_membership_grant")+" for each row execute function "+table("reject_test_grant")+"()");
    try {
      assertError(put(rank,member.id(),owner.token(),"{\"capabilities\":[\"ADD_ENTRY\",\"RATE_OWN\"]}",Map.of()),500,"ranking.operation_failed");
      assertThat(authorizationRows()).isEqualTo(before);
    } finally {
      jdbc.execute("drop trigger reject_test_grant on "+table("ranking_membership_grant"));
      jdbc.execute("drop function "+table("reject_test_grant")+"()");
    }
  }

  @Test void simultaneousReplacementsCannotMixGrantSets() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ");
    var futures=new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    for(int i=0;i<6;i++) futures.add(http.sendAsync(putRequest(rank,member.id(),owner.token(),i%2==0
        ? "{\"capabilities\":[\"READ\",\"ADD_ENTRY\"]}" : "{\"capabilities\":[\"RATE_OWN\",\"INVITE_MEMBER\"]}",Map.of()),HttpResponse.BodyHandlers.ofString()));
    for(var future:futures) assertUpdated(response(future.get(15,java.util.concurrent.TimeUnit.SECONDS)));
    assertThat(grants(rank,member)).isIn(List.of("ADD_ENTRY","READ"),List.of("INVITE_MEMBER","RATE_OWN"));
  }

  @Test void blockedGrantUpdateRechecksCommittedOwnershipTransfer() throws Exception {
    var owner=user(false); var successor=user(false); var member=user(false);
    UUID rank=ranking(owner,"PRIVATE"); membership(rank,successor,"READ"); membership(rank,member,"READ");
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try(var update=connection.prepareStatement("update "+table("ranking")+" set owner_user_id=? where id=?")) {
        update.setObject(1,successor.id());update.setObject(2,rank);update.executeUpdate();
        var pending=http.sendAsync(putRequest(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),HttpResponse.BodyHandlers.ofString());
        awaitRankingLock(connection); connection.commit();
        assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),404,"ranking.not_found");
      }
    }
    assertThat(grants(rank,member)).containsExactly("READ");
  }

  @Test void blockedGrantUpdateRechecksCommittedDeparture() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ");
    try(var connection=dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try(var lock=connection.prepareStatement("select id from "+table("ranking")+" where id=? for update");
          var update=connection.prepareStatement("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?")) {
        lock.setObject(1,rank);try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
        update.setObject(1,rank);update.setObject(2,member.id());update.executeUpdate();
        var pending=http.sendAsync(putRequest(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),HttpResponse.BodyHandlers.ofString());
        awaitRankingLock(connection); connection.commit();
        assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),404,"ranking.not_found");
      }
    }
    assertThat(grants(rank,member)).containsExactly("READ");
  }

  @Test void lockedOwnerCannotReuseTokenToMutate() throws Exception {
    var owner=user(false); var member=user(false); UUID rank=ranking(owner,"PRIVATE"); membership(rank,member,"READ");
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());
    var before=authorizationRows();
    assertError(put(rank,member.id(),owner.token(),"{\"capabilities\":[]}",Map.of()),404,"ranking.not_found");
    assertThat(authorizationRows()).isEqualTo(before);
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
