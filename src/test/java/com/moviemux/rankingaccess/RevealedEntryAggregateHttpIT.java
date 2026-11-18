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
class RevealedEntryAggregateHttpIT {
  private static final String SCHEMA = "revealed_aggregate_test_" + UUID.randomUUID().toString().replace("-", "");
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


  @Test void aggregationUsesAllRevealedNonNullVotesNotAverageOfRoundAverages() throws Exception {
    var owner=user(false);var second=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,second,"READ");
    UUID r1=round(rank,owner,true),entry=entryOf(r1),r2=additionalRound(rank,entry,2,true);
    UUID a=author(rank,owner),b=author(rank,second);
    rating(rank,r1,a,2);rating(rank,r1,b,8);rating(rank,r2,a,9);
    var before=snapshot();assertAggregate(get(rank,entry,second.token()),entry,"19","3",false);
    assertThat(snapshot()).containsExactlyElementsOf(before);
    assertAggregate(get(rank,entry,owner.token()),entry,"19","3",false);
  }

  @Test void nullScoresAreExcludedAndHistoricalOutliersUseBigIntegerTotals() throws Exception {
    var owner=user(false);var other=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,other,"READ");
    UUID r1=round(rank,owner,true),entry=entryOf(r1),r2=additionalRound(rank,entry,2,true),r3=additionalRound(rank,entry,3,true);
    UUID a=author(rank,owner),b=author(rank,other);
    rating(rank,r1,a,Integer.MAX_VALUE);rating(rank,r1,b,Integer.MAX_VALUE);rating(rank,r2,a,-17);rating(rank,r2,b,null);rating(rank,r3,a,0);
    var before=snapshot();assertAggregate(get(rank,entry,owner.token()),entry,"4294967277","4",false);
    assertThat(snapshot()).containsExactlyElementsOf(before);
  }

  @Test void hiddenAndUnknownVoteEditsAddsAndDeletesCannotChangeAggregateOrPartial() throws Exception {
    var owner=user(false);var other=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,other,"READ");
    UUID visible=round(rank,owner,true),entry=entryOf(visible),hidden=additionalRound(rank,entry,2,false),unknown=additionalRound(rank,entry,3,null);
    UUID a=author(rank,owner),b=author(rank,other);rating(rank,visible,a,7);
    var expected=get(rank,entry,other.token());assertAggregate(expected,entry,"7","1",true);
    rating(rank,hidden,a,1);rating(rank,unknown,b,10);
    assertThat(get(rank,entry,other.token()).body()).isEqualTo(expected.body());
    jdbc.update("update "+table("ranking_movie_rating")+" set score=2147483647 where round_id in (?,?)",hidden,unknown);
    rating(rank,hidden,b,null);rating(rank,unknown,a,-99);
    assertThat(get(rank,entry,other.token()).body()).isEqualTo(expected.body());
    jdbc.update("delete from "+table("ranking_movie_rating")+" where round_id in (?,?)",hidden,unknown);
    var before=snapshot();assertThat(get(rank,entry,other.token()).body()).isEqualTo(expected.body());assertThat(snapshot()).containsExactlyElementsOf(before);
  }

  @Test void zeroVotesYieldExactZeroWithoutAverageAndEmptyHiddenRoundsStillMarkPartial() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),visible=round(rank,owner,true),entry=entryOf(visible),a=author(rank,owner);
    assertAggregate(get(rank,entry,owner.token()),entry,"0","0",false);
    rating(rank,visible,a,null);assertAggregate(get(rank,entry,owner.token()),entry,"0","0",false);
    UUID emptyHidden=additionalRound(rank,entry,2,false);assertAggregate(get(rank,entry,owner.token()),entry,"0","0",true);
    jdbc.update("update "+table("ranking_movie_round")+" set revealed=null where round_id=?",emptyHidden);
    assertAggregate(get(rank,entry,owner.token()),entry,"0","0",true);
    jdbc.update("update "+table("ranking_movie_round")+" set revealed=true where round_id=?",emptyHidden);
    assertAggregate(get(rank,entry,owner.token()),entry,"0","0",false);
  }

  @Test void otherRankingAndEntryVotesNeverContributeAndMissingInaccessibleResponsesMatch() throws Exception {
    var owner=user(false);var other=user(false);var admin=user(true);UUID a=ranking(owner,"PRIVATE"),b=ranking(other,"PUBLIC");
    UUID ra=round(a,owner,true),ea=entryOf(ra),rb=round(b,other,true),eb=entryOf(rb);
    UUID aa=author(a,owner),ab=author(b,other);rating(a,ra,aa,4);rating(b,rb,ab,1000);
    UUID unrelated=round(a,owner,true);rating(a,unrelated,aa,100);
    assertAggregate(get(a,ea,owner.token()),ea,"4","1",false);
    var denied=get(b,eb,owner.token());assertNotFound(denied);
    for(var result:List.of(get(a,eb,owner.token()),get(a,UUID.randomUUID(),owner.token()),get(a,ea,admin.token()),get(UUID.randomUUID(),ea,owner.token()))) {
      assertNotFound(result);assertThat(result.body()).isEqualTo(denied.body());
    }
  }

  @Test void activeReadIsRequiredAndRevocationAppliesToSameTokenWithoutAdminOverride() throws Exception {
    var owner=user(false);var reader=user(false);UUID rank=ranking(owner,"PRIVATE"),r=round(rank,owner,true),entry=entryOf(r);membership(rank,reader,"RATE_OWN");
    assertNotFound(get(rank,entry,reader.token()));grant(rank,reader,"READ");assertAggregate(get(rank,entry,reader.token()),entry,"0","0",false);
    jdbc.update("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='READ'",rank,reader.id());assertNotFound(get(rank,entry,reader.token()));
    grant(rank,reader,"READ");jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,reader.id());assertNotFound(get(rank,entry,reader.token()));
    assertAggregate(get(rank,entry,owner.token()),entry,"0","0",false);
  }

  @Test void archivedHistoryIsReadableButRemovedAndUnauthenticatedAccessIsNot() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),r=round(rank,owner,null),entry=entryOf(r);
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);
    var before=snapshot();assertAggregate(get(rank,entry,owner.token()),entry,"0","0",true);assertThat(snapshot()).containsExactlyElementsOf(before);
    assertThat(get(rank,entry,null).status()).isIn(401,403);assertThat(get(rank,entry,"invalid-token").status()).isIn(401,403);
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());assertThat(get(rank,entry,owner.token()).status()).isIn(401,403);
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=0 where user_id=?",owner.id());
    jdbc.update("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp(),removal_token=? where ranking_id=? and entry_id=?",UUID.randomUUID(),rank,entry);
    before=snapshot();assertNotFound(get(rank,entry,owner.token()));assertThat(snapshot()).containsExactlyElementsOf(before);
  }

  private UUID entryOf(UUID round){return jdbc.queryForObject("select entry_id from "+table("ranking_movie_round")+" where round_id=?",UUID.class,round);}
  private UUID additionalRound(UUID rank,UUID entry,long number,Boolean revealed){
    UUID id=UUID.randomUUID();jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,created_at,revealed) values (?,?,?,?,'2020-01-01T00:00:00Z',?)",rank,entry,id,number,revealed);return id;
  }
  private List<String> snapshot(){return rows(List.of("movie","movie_note","ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));}
  private ApiResponse get(UUID rank,UUID entry,String token)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/entries/"+entry+"/ratings/aggregate"))
        .timeout(Duration.ofSeconds(10)).GET().header("Accept","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);return response(http.send(request.build(),HttpResponse.BodyHandlers.ofString()));
  }
  private void assertAggregate(ApiResponse result,UUID entry,String total,String votes,boolean partial){
    assertThat(result.status()).isEqualTo(200);assertNoStore(result);
    assertThat(fields(result.body())).containsExactlyInAnyOrder("entry_id","total","votes","partial");
    assertThat(result.body().required("entry_id").asText()).isEqualTo(entry.toString());
    assertThat(result.body().required("total").isIntegralNumber()).isTrue();assertThat(result.body().required("votes").isIntegralNumber()).isTrue();
    assertThat(result.body().required("total").bigIntegerValue()).isEqualTo(new java.math.BigInteger(total));
    assertThat(result.body().required("votes").bigIntegerValue()).isEqualTo(new java.math.BigInteger(votes));
    assertThat(result.body().required("partial").asBoolean()).isEqualTo(partial);
  }
  private void assertNotFound(ApiResponse result){assertThat(result.status()).isEqualTo(404);assertThat(fields(result.body())).containsExactly("code");assertThat(result.body().required("code").asText()).isEqualTo("ranking.not_found");assertNoStore(result);}
  private void assertNoStore(ApiResponse result){assertThat(result.headers().firstValue("Cache-Control")).hasValueSatisfying(v->assertThat(v).contains("no-store"));}

  private UUID round(UUID rank,Account owner,Boolean revealed) {
    UUID movie=UUID.randomUUID(),entry=UUID.randomUUID(),round=UUID.randomUUID();
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,english_title,portuguese_title,user_id) values (?,'2020-01-01T00:00:00Z','Synthetic','Synthetic','Synthetic','Synthetic',?)",movie,owner.id());
    jdbc.update("insert into "+table("movie_note")+" (movie_id,user_id,created_at,note) values (?,?,'2020-01-01T00:00:00Z',-99)",movie,owner.id());
    jdbc.update("insert into "+table("ranking_movie_entry")+" (ranking_id,entry_id,movie_id,created_at) values (?,?,?,'2020-01-01T00:00:00Z')",rank,entry,movie);
    jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,description,created_at,updated_at,revealed) values (?,?,?,1,'Synthetic historical round','2020-01-01T00:00:00Z','2020-01-02T00:00:00Z',?)",rank,entry,round,revealed);return round;
  }
  private UUID author(UUID rank,Account actor) {UUID id=UUID.randomUUID();jdbc.update("insert into "+table("ranking_author_identity")+" (ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,id,actor.id(),UUID.randomUUID());return id;}
  private void rating(UUID rank,UUID round,UUID author,Integer score) {jdbc.update("insert into "+table("ranking_movie_rating")+" (ranking_id,round_id,author_id,score,created_at,updated_at) values (?,?,?,?,'2020-01-01T00:00:00Z','2020-01-02T00:00:00Z')",rank,round,author,score);}
  private List<String> ratingRows(UUID author) {return jdbc.queryForList("select row_to_json(t)::text from "+table("ranking_movie_rating")+" t where author_id=? order by round_id",String.class,author);}
  private List<String> unrelatedRows(){return rows(List.of("movie","movie_note","ranking_movie_entry","ranking_movie_round"));}
  private List<String> targetRows(){return rows(List.of("ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));}
  private List<String> rows(List<String> tables){var result=new ArrayList<String>();for(String name:tables)result.addAll(jdbc.queryForList("select '"+name+":'||row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));return result;}
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
