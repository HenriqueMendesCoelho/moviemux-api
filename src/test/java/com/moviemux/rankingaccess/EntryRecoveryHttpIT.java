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
class EntryRecoveryHttpIT {
  private static final String SCHEMA = "entry_recovery_test_" + UUID.randomUUID().toString().replace("-", "");
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


  @Test void versionedCycleRequiresConfirmationAndNeverExtendsRemovalDeadline() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");var item=fixture(rank,owner);var history=history();
    var preview=preview(rank,item.entry(),owner);assertState(preview,item.entry(),0,false);
    assertThat(preview.body().toString()).doesNotContain(item.author().toString(),item.round().toString(),"score","rating","count");
    assertThat(call("DELETE",base(rank,item.entry()),owner.token(),"{\"expected_version\":0,\"confirm\":false}").status()).isEqualTo(400);
    var removed=remove(rank,item.entry(),owner,0);assertState(removed,item.entry(),1,true);
    assertThat(Duration.between(instant(removed,"removed_at"),instant(removed,"expires_at"))).isEqualTo(Duration.ofHours(24));
    String token=removed.body().required("removal_token").asText();
    assertError(remove(rank,item.entry(),owner,0),409,"ranking.entry_state_stale");
    assertThat(preview(rank,item.entry(),owner).body()).isEqualTo(removed.body());
    assertError(restore(rank,item.entry(),owner,UUID.randomUUID().toString()),409,"ranking.entry_state_stale");
    assertState(restore(rank,item.entry(),owner,token),item.entry(),2,false);
    assertError(restore(rank,item.entry(),owner,token),409,"ranking.entry_state_stale");
    assertError(remove(rank,item.entry(),owner,0),409,"ranking.entry_state_stale");
    var next=remove(rank,item.entry(),owner,2);assertState(next,item.entry(),3,true);
    assertThat(next.body().required("removal_token").asText()).isNotEqualTo(token);
    assertError(restore(rank,item.entry(),owner,token),409,"ranking.entry_state_stale");
    assertThat(history()).containsExactlyElementsOf(history);
  }

  @Test void destructiveConfirmationAndVersionMustBeExplicitAndWellTyped() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");var item=fixture(rank,owner);var before=entryRows();
    for(String body:List.of("{}","{\"expected_version\":0}","{\"confirm\":true}","{\"expected_version\":-1,\"confirm\":true}",
        "{\"expected_version\":\"0\",\"confirm\":true}","{\"expected_version\":0.0,\"confirm\":true}",
        "{\"expected_version\":0,\"confirm\":\"true\"}","{\"expected_version\":0,\"confirm\":false}"))
      assertError(call("DELETE",base(rank,item.entry()),owner.token(),body),400,"ranking.invalid_recovery_request");
    for(String body:List.of("{}","{\"removal_token\":null}","{\"removal_token\":\"wrong\"}"))
      assertError(call("POST",base(rank,item.entry())+"/restore",owner.token(),body),400,"ranking.invalid_recovery_request");
    assertThat(entryRows()).containsExactlyElementsOf(before);
  }

  @Test void removalOwnCapabilityUsesOriginalContextualAuthorWithoutReadOrGlobalAdminOverride() throws Exception {
    var owner=user(false);var own=user(false);var moderator=user(false);var reader=user(false);var admin=user(true);
    UUID rank=ranking(owner,"PRIVATE"),other=ranking(admin,"PRIVATE");
    membership(rank,own,"REMOVE_OWN_ENTRY");membership(rank,moderator,"REMOVE_ENTRY");membership(rank,reader,"READ");
    var mine=fixture(rank,own);var owners=fixture(rank,owner);var elsewhere=fixture(other,admin);
    assertState(preview(rank,mine.entry(),own),mine.entry(),0,false);
    assertError(preview(rank,owners.entry(),own),404,"ranking.not_found");
    assertError(preview(rank,mine.entry(),reader),404,"ranking.not_found");
    assertError(preview(rank,mine.entry(),admin),404,"ranking.not_found");
    assertError(preview(rank,elsewhere.entry(),owner),404,"ranking.not_found");
    assertError(preview(UUID.randomUUID(),mine.entry(),owner),404,"ranking.not_found");
    assertState(remove(rank,mine.entry(),own,0),mine.entry(),1,true);
    assertState(remove(rank,owners.entry(),moderator,0),owners.entry(),1,true);
    assertState(preview(rank,mine.entry(),owner),mine.entry(),1,true);
  }

  @Test void removedEntriesDisappearFromReadsAndRejectRatingWithoutChangingHiddenHistory() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");var item=fixture(rank,owner);var before=history();
    var removed=remove(rank,item.entry(),owner,0);assertState(removed,item.entry(),1,true);
    var entries=call("GET","/rankings/"+rank+"/entries",owner.token(),null);
    assertThat(entries.status()).isEqualTo(200);assertThat(entries.body().required("items").size()).isZero();
    assertError(call("GET",base(rank,item.entry())+"/rounds",owner.token(),null),404,"ranking.not_found");
    for(String method:List.of("POST","PATCH"))assertError(call(method,"/rankings/"+rank+"/rounds/"+item.round()+"/rating/me",owner.token(),"{\"score\":5}"),404,"ranking.not_found");
    assertThat(history()).containsExactlyElementsOf(before);
    assertState(restore(rank,item.entry(),owner,removed.body().required("removal_token").asText()),item.entry(),2,false);
    assertThat(call("GET","/rankings/"+rank+"/entries",owner.token(),null).body().required("items").size()).isEqualTo(1);
    assertThat(history()).containsExactlyElementsOf(before);
  }

  @Test void addEntryCapabilityRestoresSameEntryAndHistoryButCannotExtendAnExpiredWindow() throws Exception {
    var owner=user(false);var adder=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,adder,"ADD_ENTRY");
    var item=fixture(rank,owner);var before=history();var removed=remove(rank,item.entry(),owner,0);
    assertState(removed,item.entry(),1,true);String old=removed.body().required("removal_token").asText();
    var readded=readd(rank,item.movie(),adder);assertThat(readded.status()).isEqualTo(200);
    assertThat(fields(readded.body())).containsExactly("entry_id");assertThat(readded.body().required("entry_id").asText()).isEqualTo(item.entry().toString());
    assertState(preview(rank,item.entry(),owner),item.entry(),2,false);
    assertError(restore(rank,item.entry(),owner,old),409,"ranking.entry_state_stale");
    assertThat(readd(rank,item.movie(),adder).status()).isEqualTo(200);assertState(preview(rank,item.entry(),owner),item.entry(),2,false);
    var second=remove(rank,item.entry(),owner,2);assertState(second,item.entry(),3,true);
    jdbc.update("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp()-interval '24 hours' where ranking_id=? and entry_id=?",rank,item.entry());
    var expired=preview(rank,item.entry(),owner);assertState(expired,item.entry(),3,true);
    assertError(restore(rank,item.entry(),owner,second.body().required("removal_token").asText()),409,"ranking.entry_recovery_expired");
    assertError(readd(rank,item.movie(),adder),409,"ranking.entry_recovery_expired");
    assertThat(preview(rank,item.entry(),owner).body()).isEqualTo(expired.body());
    assertThat(history()).containsExactlyElementsOf(before);
  }

  @Test void archivedAndBlockedActorsCannotMutateLifecycle() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");var item=fixture(rank,owner);
    var removed=remove(rank,item.entry(),owner,0);String token=removed.body().required("removal_token").asText();
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);
    var before=entryRows();
    assertError(restore(rank,item.entry(),owner,token),409,"ranking.archived_read_only");
    assertError(remove(rank,item.entry(),owner,1),409,"ranking.archived_read_only");
    assertError(readd(rank,item.movie(),owner),409,"ranking.archived_read_only");
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());
    assertThat(remove(rank,item.entry(),owner,1).status()).isIn(401,403);
    assertThat(call("GET",base(rank,item.entry())+"/removal-preview",null,null).status()).isIn(401,403);
    assertThat(entryRows()).containsExactlyElementsOf(before);
  }

  @Test void deleteRechecksCommittedRevocationAfterRealRankingLockWait() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,actor,"REMOVE_ENTRY");var item=fixture(rank,owner);
    var before=entryRows();
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var pending=http.sendAsync(request("DELETE",base(rank,item.entry()),actor.token(),"{\"expected_version\":0,\"confirm\":true}"),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,1);
      try(var revoke=blocker.prepareStatement("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='REMOVE_ENTRY'")) {
        revoke.setObject(1,rank);revoke.setObject(2,actor.id());revoke.executeUpdate();
      }
      blocker.commit();assertError(response(pending.get(15,java.util.concurrent.TimeUnit.SECONDS)),404,"ranking.not_found");
    }
    assertThat(entryRows()).containsExactlyElementsOf(before);
  }

  @Test void concurrentRestoreAndReaddPreserveOneHistoryAndOnlyOneRestorationVersion() throws Exception {
    var owner=user(false);var adder=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,adder,"ADD_ENTRY");var item=fixture(rank,owner);
    var removed=remove(rank,item.entry(),owner,0);String token=removed.body().required("removal_token").asText();var before=history();
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);lockRanking(blocker,rank);
      var restore=http.sendAsync(request("POST",base(rank,item.entry())+"/restore",owner.token(),"{\"removal_token\":\""+token+"\"}"),HttpResponse.BodyHandlers.ofString());
      var add=http.sendAsync(request("POST","/rankings/"+rank+"/entries/catalog",adder.token(),"{\"movie_id\":\""+item.movie()+"\"}"),HttpResponse.BodyHandlers.ofString());
      awaitBlockedRequests(blocker,2);blocker.commit();
      var restored=response(restore.get(15,java.util.concurrent.TimeUnit.SECONDS));var readded=response(add.get(15,java.util.concurrent.TimeUnit.SECONDS));
      assertThat(readded.status()).isEqualTo(200);assertThat(readded.body().required("entry_id").asText()).isEqualTo(item.entry().toString());
      if(restored.status()==200)assertState(restored,item.entry(),2,false);else assertError(restored,409,"ranking.entry_state_stale");
    }
    assertState(preview(rank,item.entry(),owner),item.entry(),2,false);assertThat(history()).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForObject("select count(*) from "+table("ranking_movie_entry"),Integer.class)).isEqualTo(1);
  }

  private Item fixture(UUID rank,Account actor) {
    UUID movie=UUID.randomUUID(),entry=UUID.randomUUID(),round=UUID.randomUUID();
    var found=jdbc.queryForList("select author_id from "+table("ranking_author_identity")+" where ranking_id=? and account_id=?",UUID.class,rank,actor.id());
    UUID author=found.isEmpty()?UUID.randomUUID():found.get(0);
    if(found.isEmpty())jdbc.update("insert into "+table("ranking_author_identity")+" (ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,author,actor.id(),UUID.randomUUID());
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,'2020-01-01T00:00:00Z','Synthetic','Synthetic','Synthetic','Synthetic',?)",movie,actor.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,'2020-01-01T00:00:00Z',99)",movie,actor.id());
    jdbc.update("insert into "+table("ranking_movie_entry")+" (ranking_id,entry_id,movie_id,added_by_author_id,created_at) values (?,?,?,?,'2020-01-01T00:00:00Z')",rank,entry,movie,author);
    jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,description,created_at,revealed) values (?,?,?,1,'Synthetic hidden history','2020-01-01T00:00:00Z',false)",rank,entry,round);
    jdbc.update("insert into "+table("ranking_movie_rating")+" (ranking_id,round_id,author_id,score,created_at) values (?,?,?,99,'2020-01-01T00:00:00Z')",rank,round,author);
    return new Item(movie,entry,round,author);
  }
  private List<String> history(){return rows(List.of("movie","movie_note","ranking_movie_round","ranking_movie_rating","ranking_author_identity"));}
  private List<String> entryRows(){return rows(List.of("ranking_movie_entry"));}
  private List<String> rows(List<String> tables){var result=new ArrayList<String>();for(String name:tables)result.addAll(jdbc.queryForList("select '"+name+":'||row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));return result;}
  private static String base(UUID rank,UUID entry){return "/rankings/"+rank+"/entries/"+entry;}
  private ApiResponse preview(UUID rank,UUID entry,Account actor)throws Exception{return call("GET",base(rank,entry)+"/removal-preview",actor.token(),null);}
  private ApiResponse remove(UUID rank,UUID entry,Account actor,long version)throws Exception{return call("DELETE",base(rank,entry),actor.token(),"{\"expected_version\":"+version+",\"confirm\":true}");}
  private ApiResponse restore(UUID rank,UUID entry,Account actor,String token)throws Exception{return call("POST",base(rank,entry)+"/restore",actor.token(),"{\"removal_token\":\""+token+"\"}");}
  private ApiResponse readd(UUID rank,UUID movie,Account actor)throws Exception{return call("POST","/rankings/"+rank+"/entries/catalog",actor.token(),"{\"movie_id\":\""+movie+"\"}");}
  private HttpRequest request(String method,String path,String token,String body){
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
        .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
    if(token!=null)builder.header("Authorization","Bearer "+token);return builder.build();
  }
  private ApiResponse call(String method,String path,String token,String body)throws Exception{return response(http.send(request(method,path,token,body),HttpResponse.BodyHandlers.ofString()));}
  private java.time.Instant instant(ApiResponse result,String field){return java.time.OffsetDateTime.parse(result.body().required(field).asText()).toInstant();}
  private void assertState(ApiResponse result,UUID entry,long version,boolean removed){
    assertThat(result.status()).isEqualTo(200);assertNoStore(result);
    assertThat(fields(result.body())).containsExactlyInAnyOrder("entry_id","version","removed_at","expires_at","removal_token","warning_code","recovery_seconds");
    assertThat(result.body().required("entry_id").asText()).isEqualTo(entry.toString());assertThat(result.body().required("version").asLong()).isEqualTo(version);
    assertThat(result.body().required("warning_code").asText()).isEqualTo("ranking.entry_removal_permanent_after_retention");
    assertThat(result.body().required("recovery_seconds").asLong()).isEqualTo(86400);
    for(String field:List.of("removed_at","expires_at","removal_token"))assertThat(result.body().required(field).isNull()).isEqualTo(!removed);
    if(removed)assertThat(UUID.fromString(result.body().required("removal_token").asText())).isNotNull();
  }
  private void assertError(ApiResponse result,int status,String code){assertThat(result.status()).isEqualTo(status);assertThat(fields(result.body())).containsExactly("code");assertThat(result.body().required("code").asText()).isEqualTo(code);assertNoStore(result);}
  private void assertNoStore(ApiResponse result){assertThat(result.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));}
  private record Item(UUID movie,UUID entry,UUID round,UUID author){}

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
