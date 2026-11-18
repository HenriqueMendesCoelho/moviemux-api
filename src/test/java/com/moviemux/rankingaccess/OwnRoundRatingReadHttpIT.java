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
class OwnRoundRatingReadHttpIT {
  private static final String SCHEMA = "own_round_read_test_" + UUID.randomUUID().toString().replace("-", "");
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


  @Test void onlyOwnHistoricalScoreIsReturnedIncludingNullAndOutliersWithoutChangingAnything() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,actor,"READ");
    UUID mine=author(rank,actor),theirs=author(rank,owner);
    for(Integer score:java.util.Arrays.asList(null,-17,0,10,123)) {
      UUID round=round(rank,owner,false);rating(rank,round,mine,score);rating(rank,round,theirs,8);
      var before=snapshot();var result=get(rank,round,actor.token(),Map.of());assertRating(result,round,score);
      assertThat(result.body().toString()).doesNotContain(mine.toString(),theirs.toString(),actor.id().toString(),owner.id().toString(),actor.email(),"revealed","average","count","author");
      assertThat(snapshot()).containsExactlyElementsOf(before);
    }
  }

  @Test void hiddenRevealedAndUnknownRevealStatesDoNotBlockOwnHistoricalReadOrExposeOthers() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,actor,"READ");
    UUID own=author(rank,actor),other=author(rank,owner),round=round(rank,owner,false);
    rating(rank,round,own,3);rating(rank,round,other,10);
    var expected=get(rank,round,actor.token(),Map.of());assertRating(expected,round,3);
    for(Boolean reveal:java.util.Arrays.asList(null,true,false)) {
      jdbc.update("update "+table("ranking_movie_round")+" set revealed=? where ranking_id=? and round_id=?",reveal,rank,round);
      jdbc.update("update "+table("ranking_movie_rating")+" set score=score-1 where author_id=?",other);
      var before=snapshot();assertThat(get(rank,round,actor.token(),Map.of()).body()).isEqualTo(expected.body());assertThat(snapshot()).containsExactlyElementsOf(before);
    }
  }

  @Test void readCapabilityIsRequiredRatherThanRateOwnAndRevocationAppliesToSameToken() throws Exception {
    var owner=user(false);var actor=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false);membership(rank,actor,"RATE_OWN");
    UUID own=author(rank,actor);rating(rank,round,own,5);
    assertNotFound(get(rank,round,actor.token(),Map.of()));grant(rank,actor,"READ");assertRating(get(rank,round,actor.token(),Map.of()),round,5);
    jdbc.update("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='READ'",rank,actor.id());
    assertNotFound(get(rank,round,actor.token(),Map.of()));grant(rank,actor,"READ");
    jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,actor.id());
    assertNotFound(get(rank,round,actor.token(),Map.of()));
    UUID ownerAuthor=author(rank,owner);rating(rank,round,ownerAuthor,7);assertRating(get(rank,round,owner.token(),Map.of()),round,7);
  }

  @Test void absenceAndCrossContextDenialsAreUniformAndAdminOrForgedMetadataCannotBorrowVotes() throws Exception {
    var owner=user(false);var actor=user(false);var admin=user(true);
    UUID a=ranking(owner,"PRIVATE"),b=ranking(admin,"PUBLIC"),roundA=round(a,owner,false),roundB=round(b,admin,true);
    membership(a,actor,"READ");UUID ownerAuthor=author(a,owner);rating(a,roundA,ownerAuthor,9);
    var missingOwn=get(a,roundA,actor.token(),Map.of());assertNotFound(missingOwn);
    for(var response:List.of(get(a,UUID.randomUUID(),actor.token(),Map.of()),get(b,roundB,actor.token(),Map.of()),
        get(a,roundB,owner.token(),Map.of()),get(a,roundA,admin.token(),Map.of()),
        get(a,roundA,actor.token(),Map.of("X-User-Id",owner.id().toString(),"X-Author-Id",ownerAuthor.toString(),"X-Ranking-Role","OWNER")))) {
      assertNotFound(response);assertThat(response.body()).isEqualTo(missingOwn.body());
    }
    assertRating(get(a,roundA,owner.token(),Map.of()),roundA,9);
  }

  @Test void archivedHistoryRemainsReadableButRemovedEntryDoesNot() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,null),own=author(rank,owner);rating(rank,round,own,null);
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);
    var before=snapshot();assertRating(get(rank,round,owner.token(),Map.of()),round,null);assertThat(snapshot()).containsExactlyElementsOf(before);
    // Synthetic marker fixture only; no removal of immutable archived history is invoked.
    jdbc.update("update "+table("ranking_movie_entry")+" set removed_at=clock_timestamp(),removal_token=? where ranking_id=?",UUID.randomUUID(),rank);
    var removed=snapshot();assertNotFound(get(rank,round,owner.token(),Map.of()));assertThat(snapshot()).containsExactlyElementsOf(removed);
  }

  @Test void anonymousInvalidAndCurrentlyLockedAccountCannotReadEvenOwnScore() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),round=round(rank,owner,false),own=author(rank,owner);rating(rank,round,own,6);
    assertRating(get(rank,round,owner.token(),Map.of()),round,6);
    assertThat(get(rank,round,null,Map.of()).status()).isIn(401,403);
    assertThat(get(rank,round,"invalid-token",Map.of()).status()).isIn(401,403);
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());
    var before=snapshot();assertThat(get(rank,round,owner.token(),Map.of()).status()).isIn(401,403);assertThat(snapshot()).containsExactlyElementsOf(before);
  }

  private List<String> snapshot(){return rows(List.of("movie","movie_note","ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"));}
  private ApiResponse get(UUID rank,UUID round,String token,Map<String,String> headers)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith/rankings/"+rank+"/rounds/"+round+"/rating/me"))
        .timeout(Duration.ofSeconds(10)).GET().header("Accept","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);headers.forEach(request::header);
    return response(http.send(request.build(),HttpResponse.BodyHandlers.ofString()));
  }
  private void assertRating(ApiResponse result,UUID round,Integer score){
    assertThat(result.status()).isEqualTo(200);assertNoStore(result);assertThat(fields(result.body())).containsExactlyInAnyOrder("round_id","score");
    assertThat(result.body().required("round_id").asText()).isEqualTo(round.toString());
    if(score==null)assertThat(result.body().required("score").isNull()).isTrue();else assertThat(result.body().required("score").asInt()).isEqualTo(score);
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
