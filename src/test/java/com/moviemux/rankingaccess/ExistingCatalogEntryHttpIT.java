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
class ExistingCatalogEntryHttpIT {
  private static final String SCHEMA = "existing_catalog_entry_test_" + UUID.randomUUID().toString().replace("-", "");
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


  @Test void authorizedAddsOnlyContextualRowsAndRetryPreservesAllExistingRounds() throws Exception {
    var owner=user(false);var member=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    membership(rank,member,"ADD_ENTRY");
    var sources=sourceRows();
    var created=post(rank,member.token(),body(movie));assertEntry(created,201);
    UUID entry=UUID.fromString(created.body().required("entry_id").asText());
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_movie_round")+" where ranking_id=? and entry_id=? and round_number=1 and revealed=false and description is null",Integer.class,rank,entry)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select a.account_id from "+table("ranking_movie_entry")+" e join "+table("ranking_author_identity")+" a on a.ranking_id=e.ranking_id and a.author_id=e.added_by_author_id where e.entry_id=?",UUID.class,entry)).isEqualTo(member.id());
    jdbc.update("update "+table("ranking_movie_round")+" set revealed=true,description='synthetic original description' where entry_id=?",entry);
    jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,description,created_at,revealed) values (?,?,?,2,'synthetic second round',now(),null)",rank,entry,UUID.randomUUID());
    var beforeRetry=targetRows();
    var retry=post(rank,owner.token(),body(movie));assertEntry(retry,200);
    assertThat(retry.body()).isEqualTo(created.body());assertThat(targetRows()).isEqualTo(beforeRetry);
    assertThat(sourceRows()).isEqualTo(sources);
    // Owner does not need explicit ADD_ENTRY; the second title is still existing catalog only.
    assertEntry(post(rank,owner.token(),body(movie(owner))),201);
  }

  @Test void capabilityMembershipAndRankingIsolationAreRevalidatedWithoutAdminOverride() throws Exception {
    var owner=user(false);var member=user(false);var reader=user(false);var inactive=user(false);var admin=user(true);
    UUID a=ranking(owner,"PRIVATE"),b=ranking(admin,"PRIVATE"),movie=movie(owner);
    membership(a,member,"ADD_ENTRY");membership(a,reader,"READ");membership(a,inactive,"ADD_ENTRY");
    jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",a,inactive.id());
    var before=targetRows();
    for(var actor:List.of(reader,inactive,admin))assertError(post(a,actor.token(),body(movie)),404,"ranking.not_found");
    assertError(post(b,member.token(),body(movie)),404,"ranking.not_found");
    assertError(post(UUID.randomUUID(),member.token(),body(movie)),404,"ranking.not_found");
    assertError(post(a,member.token(),body(UUID.randomUUID())),404,"ranking.not_found");
    assertThat(targetRows()).isEqualTo(before);
    var addedA=post(a,member.token(),body(movie));var addedB=post(b,admin.token(),body(movie));
    assertEntry(addedA,201);assertEntry(addedB,201);assertThat(addedA.body()).isNotEqualTo(addedB.body());
  }

  @Test void archivalAndInvalidInputFailWithoutMutationsOrDisclosure() throws Exception {
    var owner=user(false);var stranger=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    var before=targetRows();
    for(String invalid:List.of("{}","{\"movie_id\":null}","{\"movie_id\":\"invalid\"}","{\"movie_id\":123}"))
      assertError(post(rank,owner.token(),invalid),400,"ranking.invalid_catalog_entry");
    assertThat(targetRows()).isEqualTo(before);
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);before=targetRows();
    assertError(post(rank,owner.token(),body(movie)),409,"ranking.archived_read_only");
    assertError(post(rank,stranger.token(),body(movie)),404,"ranking.not_found");
    assertThat(targetRows()).isEqualTo(before);
  }

  @Test void forgedMetadataCannotOverwriteCatalogOrConferAuthority() throws Exception {
    var owner=user(false);var member=user(false);var outsider=user(true);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    membership(rank,member,"ADD_ENTRY");var sources=sourceRows();
    String forged="{\"movie_id\":\""+movie+"\",\"user_id\":\""+owner.id()+"\",\"owner_user_id\":\""+owner.id()+"\",\"capabilities\":[\"ADD_ENTRY\"],\"portuguese_title\":\"FORGED\",\"show_notes\":true,\"ranking_id\":\""+UUID.randomUUID()+"\"}";
    assertError(post(rank,outsider.token(),forged),404,"ranking.not_found");
    assertEntry(post(rank,member.token(),forged),201);
    assertThat(sourceRows()).isEqualTo(sources);
  }

  @Test void roundInsertFailureRollsBackIdentityAndEntryTogether() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    membership(rank,actor,"ADD_ENTRY");var before=targetRows();var sources=sourceRows();
    jdbc.execute("create function "+table("synthetic_round_failure")+"() returns trigger language plpgsql as $$ begin raise exception 'synthetic private SQL failure'; end; $$");
    jdbc.execute("create trigger synthetic_round_failure before insert on "+table("ranking_movie_round")+" for each row execute function "+table("synthetic_round_failure")+"()");
    try {
      assertError(post(rank,actor.token(),body(movie)),500,"ranking.operation_failed");
      assertThat(targetRows()).isEqualTo(before);assertThat(sourceRows()).isEqualTo(sources);
    } finally {
      jdbc.execute("drop trigger synthetic_round_failure on "+table("ranking_movie_round"));
      jdbc.execute("drop function "+table("synthetic_round_failure")+"()");
    }
    assertEntry(post(rank,actor.token(),body(movie)),201);
  }

  @Test void revocationCommittedWhileRequestWaitsOnRankingLockDeniesWrite() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);membership(rank,actor,"ADD_ENTRY");
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var pending=http.sendAsync(postRequest(rank,actor.token(),body(movie)),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,1);
      try(var revoke=blocker.prepareStatement("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='ADD_ENTRY'")) {
        revoke.setObject(1,rank);revoke.setObject(2,actor.id());revoke.executeUpdate();
      }
      blocker.commit();assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),404,"ranking.not_found");
    }
    for(String name:List.of("ranking_author_identity","ranking_movie_entry","ranking_movie_round"))
      assertThat(jdbc.queryForObject("select count(*) from "+table(name),Integer.class)).isZero();
  }

  @Test void concurrentRequestsCreateOneEntryAndReturnSameIdentity() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);membership(rank,actor,"ADD_ENTRY");
    java.time.Instant waitingBeforeRelease;
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var first=http.sendAsync(postRequest(rank,actor.token(),body(movie)),HttpResponse.BodyHandlers.ofString());
      var second=http.sendAsync(postRequest(rank,actor.token(),body(movie)),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,2);
      waitingBeforeRelease=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
      blocker.commit();
      var a=response(first.get(15,java.util.concurrent.TimeUnit.SECONDS));var b=response(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(201,200);
      assertEntry(a,a.status());assertEntry(b,b.status());assertThat(a.body()).isEqualTo(b.body());
    }
    var insertedAt=jdbc.queryForObject("select created_at from "+table("ranking_movie_entry"),java.sql.Timestamp.class).toInstant();
    assertThat(insertedAt).isAfterOrEqualTo(waitingBeforeRelease);
    assertThat(jdbc.queryForObject("select created_at from "+table("ranking_movie_round"),java.sql.Timestamp.class).toInstant()).isEqualTo(insertedAt);
    for(String name:List.of("ranking_author_identity","ranking_movie_entry","ranking_movie_round"))
      assertThat(jdbc.queryForObject("select count(*) from "+table(name),Integer.class)).isEqualTo(1);
  }

  @Test void removedEntryIsHiddenAndCannotReceiveRatingsUntilSameIdentityRestored() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    var added=post(rank,owner.token(),body(movie));assertEntry(added,201);
    UUID entry=UUID.fromString(added.body().required("entry_id").asText());
    UUID round=jdbc.queryForObject("select round_id from "+table("ranking_movie_round")+" where entry_id=?",UUID.class,entry);
    var history=rows(List.of("ranking_movie_round","ranking_author_identity","ranking_movie_rating"));
    var original=jdbc.queryForMap("select added_by_author_id,created_at,updated_at from "+table("ranking_movie_entry")+" where entry_id=?",entry);
    var sources=sourceRows();
    jdbc.update("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp()-interval '1 hour',removal_token=?,lifecycle_version=lifecycle_version+1 where entry_id=?",UUID.randomUUID(),entry);
    long removedVersion=jdbc.queryForObject("select lifecycle_version from "+table("ranking_movie_entry")+" where entry_id=?",Long.class,entry);
    for(String suffix:List.of("", "?after=00000000-0000-0000-0000-000000000000")) {
      var list=contextRequest("GET","/rankings/"+rank+"/entries"+suffix,owner.token(),null);
      assertThat(list.status()).isEqualTo(200);assertThat(list.body().required("items").size()).isZero();
    }
    assertError(contextRequest("GET","/rankings/"+rank+"/entries/"+entry+"/rounds",owner.token(),null),404,"ranking.not_found");
    for(String method:List.of("POST","PATCH"))
      assertError(contextRequest(method,"/rankings/"+rank+"/rounds/"+round+"/rating/me",owner.token(),"{\"score\":8}"),404,"ranking.not_found");
    var restored=post(rank,owner.token(),body(movie));assertEntry(restored,200);assertThat(restored.body()).isEqualTo(added.body());
    assertThat(jdbc.queryForMap("select added_by_author_id,created_at,updated_at from "+table("ranking_movie_entry")+" where entry_id=?",entry)).isEqualTo(original);
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_movie_entry")+" where entry_id=? and removed_at is null and removal_token is null and lifecycle_version=?",Integer.class,entry,removedVersion+1)).isEqualTo(1);
    assertThat(rows(List.of("ranking_movie_round","ranking_author_identity","ranking_movie_rating"))).isEqualTo(history);
    var afterRestore=targetRows();assertEntry(post(rank,owner.token(),body(movie)),200);assertThat(targetRows()).isEqualTo(afterRestore);
    assertThat(sourceRows()).isEqualTo(sources);
  }

  @Test void expiredRecoveryAfterWaitingForRankingLockDoesNotDeleteOrRestoreAnything() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner);
    var added=post(rank,owner.token(),body(movie));UUID entry=UUID.fromString(added.body().required("entry_id").asText());
    jdbc.update("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp()-interval '1 hour',removal_token=?,lifecycle_version=lifecycle_version+1 where entry_id=?",UUID.randomUUID(),entry);
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var pending=http.sendAsync(postRequest(rank,owner.token(),body(movie)),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,1);
      try(var expire=blocker.prepareStatement("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp()-interval '24 hours' where entry_id=?")) {
        expire.setObject(1,entry);expire.executeUpdate();
      }
      blocker.commit();var expired=targetRows();
      assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),409,"ranking.entry_recovery_expired");
      assertThat(targetRows()).isEqualTo(expired);
      assertError(post(rank,owner.token(),body(movie)),409,"ranking.entry_recovery_expired");
      assertThat(targetRows()).isEqualTo(expired);
    }
  }

  private ApiResponse contextRequest(String method,String path,String token,String body) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
        .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    return response(http.send(request,HttpResponse.BodyHandlers.ofString()));
  }

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
          and a.query like 'select owner_user_id,archived_at from ranking where id=%for update%'
          and exists(select 1 from pg_locks l where l.pid=a.pid and l.relation=cast(? as regclass))
          and (?=any(pg_blocking_pids(a.pid)) or exists(select 1 from unnest(pg_blocking_pids(a.pid)) b(pid) where ?=any(pg_blocking_pids(b.pid))))
          """,Integer.class,table("ranking"),pid,pid);
      if(waiting!=null && waiting>=expected)return;
      Thread.sleep(20);
    }
    throw new AssertionError("Requests did not reach ranking row lock barrier");
  }
  private UUID movie(Account owner) {
    UUID id=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id,show_notes) values (?,now(),'synthetic','synthetic','synthetic','synthetic',?,false)",id,owner.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,now(),8)",id,owner.id());
    jdbc.update("insert into "+table("movie_discord")+" (movie_id,message_id) values (?,?)",id,"synthetic-"+id);
    UUID wishlist=UUID.randomUUID();
    jdbc.update("insert into "+table("user_wishlist")+" (id,created_at,name,shareable,user_id) values (?,now(),?,false,?)",wishlist,"Synthetic "+id.toString().substring(0,8),owner.id());
    long tmdb=id.getMostSignificantBits() & Long.MAX_VALUE;
    jdbc.update("insert into "+table("movie_wishlist")+" (tmdb_id,url_image) values (?,'synthetic')",tmdb);
    jdbc.update("insert into "+table("movie_wishlist_user_wishlist")+" (movie_id,wishlist_id,wishlist_order) values (?,?,3)",tmdb,wishlist);
    return id;
  }
  private List<String> sourceRows(){return rows(List.of("movie","movie_note","movie_discord","user_wishlist","movie_wishlist","movie_wishlist_user_wishlist"));}
  private List<String> targetRows(){return rows(List.of("ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));}
  private List<String> rows(List<String> tables){var result=new ArrayList<String>();for(String name:tables)result.addAll(jdbc.queryForList("select '"+name+":'||row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));return result;}
  private String body(UUID movie){return "{\"movie_id\":\""+movie+"\"}";}
  private HttpRequest postRequest(UUID rank,String token,String body){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/entries/catalog")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();}
  private ApiResponse post(UUID rank,String token,String body)throws Exception{return response(http.send(postRequest(rank,token,body),HttpResponse.BodyHandlers.ofString()));}
  private void assertEntry(ApiResponse value,int status){assertThat(value.status()).isEqualTo(status);assertThat(fields(value.body())).containsExactly("entry_id");assertThat(UUID.fromString(value.body().required("entry_id").asText())).isNotNull();assertNoStore(value);}
  private void assertError(ApiResponse value,int status,String code){assertThat(value.status()).isEqualTo(status);assertThat(fields(value.body())).containsExactly("code");assertThat(value.body().required("code").asText()).isEqualTo(code);assertNoStore(value);}
  private void assertNoStore(ApiResponse value){assertThat(value.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));}


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
