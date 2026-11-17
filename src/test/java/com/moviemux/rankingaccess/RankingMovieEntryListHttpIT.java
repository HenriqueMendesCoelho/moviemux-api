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
class RankingMovieEntryListHttpIT {
  private static final String SCHEMA = "ranking_entries_http_test_" + UUID.randomUUID().toString().replace("-", "");
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
    properties.add("spring.flyway.target", () -> "7");
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

  @Test void sharedCatalogMovieKeepsEntriesScopedAndJsonContainsOnlyMetadata() throws Exception {
    var ownerA=user(false);var ownerB=user(false);var member=user(false);
    UUID a=ranking(ownerA,"PRIVATE"),b=ranking(ownerB,"PRIVATE"),movie=movie(ownerA);
    membership(a,member,"READ");
    UUID authorA=author(a,ownerA),authorB=author(b,ownerB);
    UUID entryA=entry(a,movie,authorA),entryB=entry(b,movie,authorB);
    UUID roundA=round(a,entryA,1,false),roundB=round(b,entryB,1,true);
    rating(a,roundA,authorA,3);rating(b,roundB,authorB,9);
    var before=allRows();
    var result=list(a,member,"");assertItems(result,entryA);
    var item=result.body().required("items").get(0);
    assertThat(fields(item)).containsExactlyInAnyOrder("entry_id","movie_id","tmdb_id","original_title",
        "english_title","portuguese_title","url_image","release_date","runtime","created_at");
    assertThat(item.required("movie_id").asText()).isEqualTo(movie.toString());
    assertThat(item.required("original_title").asText()).isEqualTo("Synthetic original");
    assertThat(item.required("english_title").asText()).isEqualTo("Synthetic English");
    assertThat(item.required("portuguese_title").asText()).isEqualTo("Sintético português");
    assertThat(item.required("tmdb_id").asLong()).isPositive();
    assertThat(item.required("runtime").asInt()).isEqualTo(123);
    assertThat(item.required("release_date").asText()).isEqualTo("2020-01-02");
    assertThat(java.time.OffsetDateTime.parse(item.required("created_at").asText()).toInstant())
        .isEqualTo(java.time.Instant.parse("2024-01-02T03:04:05Z"));
    assertThat(result.body().toString()).doesNotContain(authorA.toString(),authorB.toString(),entryB.toString(),
        roundA.toString(),roundB.toString(),ownerA.id().toString(),ownerA.email(),ownerB.email(),"score","author","rating","revealed","vote");
    assertItems(list(b,ownerB,""),entryB);
    assertNotFound(list(b,member,""));
    assertThat(allRows()).containsExactlyElementsOf(before);
  }

  @Test void nullableCatalogMetadataRemainsExplicitNullWithoutDroppingTheEntry() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE"),movie=movie(owner),entry=entry(rank,movie,null);
    jdbc.update("update "+table("movie")+" set tmdb_id=null,original_title=null,url_image=null,release_date=null,runtime=null where id=?",movie);
    var result=list(rank,owner,"");assertItems(result,entry);
    var item=result.body().required("items").get(0);
    assertThat(fields(item)).containsExactlyInAnyOrder("entry_id","movie_id","tmdb_id","original_title",
        "english_title","portuguese_title","url_image","release_date","runtime","created_at");
    for(String field:List.of("tmdb_id","original_title","url_image","release_date","runtime"))
      assertThat(item.required(field).isNull()).as(field).isTrue();
  }

  @Test void hiddenNullAndRevealedRoundsOrScoresNeverChangeOrderingCountsOrMetadata() throws Exception {
    var owner=user(false);var member=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,member,"READ");
    UUID first=entry(rank,movie(owner),null),second=entry(rank,movie(owner),null);
    UUID authorOwner=author(rank,owner),authorMember=author(rank,member);
    var before=list(rank,member,"?limit=1").body();
    var allBefore=list(rank,member,"").body();
    UUID a=round(rank,first,1,false),b=round(rank,first,2,null),c=round(rank,second,1,true);
    rating(rank,a,authorOwner,1);rating(rank,a,authorMember,10);rating(rank,b,authorOwner,null);rating(rank,c,authorMember,-7);
    assertThat(list(rank,member,"?limit=1").body()).isEqualTo(before);
    assertThat(list(rank,member,"").body()).isEqualTo(allBefore);
    jdbc.update("update "+table("ranking_movie_round")+" set revealed=true where ranking_id=?",rank);
    jdbc.update("update "+table("ranking_movie_rating")+" set score=2147483647 where ranking_id=?",rank);
    assertThat(list(rank,member,"?limit=1").body()).isEqualTo(before);
    assertThat(list(rank,member,"").body()).isEqualTo(allBefore);
    jdbc.update("delete from "+table("ranking_movie_rating")+" where ranking_id=?",rank);
    jdbc.update("update "+table("ranking_movie_round")+" set revealed=null where ranking_id=?",rank);
    assertThat(list(rank,member,"").body()).isEqualTo(allBefore);
  }

  @Test void keysetIsExclusiveStableAndForeignContextCursorIsOnlyPositionNotAuthorization() throws Exception {
    var owner=user(false);var outsider=user(false);UUID rank=ranking(owner,"PRIVATE"),other=ranking(outsider,"PRIVATE");
    var expected=new ArrayList<String>();
    for(int number:new int[]{1,2,4,5,6}) {
      UUID id=orderedId(number);entry(rank,id,movie(owner),null);expected.add(id.toString());
    }
    UUID foreign=orderedId(3);entry(other,foreign,movie(outsider),null);
    var actual=new ArrayList<String>();String cursor=null;int pages=0;
    do {
      var result=list(rank,owner,"?limit=2"+(cursor==null?"":"&after="+cursor));
      assertThat(result.status()).isEqualTo(200);
      assertThat(fields(result.body())).containsExactlyInAnyOrder("items","next_cursor");
      var ids=itemIds(result);assertThat(ids.size()).isBetween(1,2);actual.addAll(ids);
      var next=result.body().required("next_cursor");cursor=next.isNull()?null:next.asText();
      if(cursor!=null)assertThat(cursor).isEqualTo(ids.get(ids.size()-1));
      assertThat(++pages).isLessThanOrEqualTo(3);
    } while(cursor!=null);
    assertThat(actual).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    var foreignResult=list(rank,owner,"?after="+foreign);
    assertItems(foreignResult,orderedId(4),orderedId(5),orderedId(6));
    jdbc.update("delete from "+table("ranking_movie_entry")+" where ranking_id=? and entry_id=?",other,foreign);
    assertThat(list(rank,owner,"?after="+foreign).body()).isEqualTo(foreignResult.body());
    assertNotFound(list(other,owner,"?after="+orderedId(1)));
    assertItems(list(rank,owner,"?after="+orderedId(6)));
  }

  @Test void defaultPageLimitsMalformedQueriesAndEmptyAccessibleRanking() throws Exception {
    var owner=user(false);UUID rank=ranking(owner,"PRIVATE");
    assertItems(list(rank,owner,""));
    for(int i=1;i<=21;i++) entry(rank,orderedId(i),movie(owner),null);
    var first=list(rank,owner,"");assertThat(first.body().required("items").size()).isEqualTo(20);
    assertThat(first.body().required("next_cursor").asText()).isEqualTo(orderedId(20).toString());
    assertItems(list(rank,owner,"?after="+orderedId(20)),orderedId(21));
    assertThat(list(rank,owner,"?limit=100").body().required("items").size()).isEqualTo(21);
    for(String query:List.of("?limit=0","?limit=-1","?limit=101","?limit=word","?after=wrong")) {
      var invalid=list(rank,owner,query);assertThat(invalid.status()).isEqualTo(400);
      assertThat(invalid.body().required("code").asText()).isEqualTo("ranking.invalid_page");
    }
  }

  @Test void sameTokenImmediatelyReflectsReadGrantAndMembershipRevocation() throws Exception {
    var owner=user(false);var member=user(false);UUID rank=ranking(owner,"PRIVATE");membership(rank,member,"READ","RATE_OWN");
    UUID entry=entry(rank,movie(owner),null);assertItems(list(rank,owner,""),entry);assertItems(list(rank,member,""),entry);
    jdbc.update("delete from "+table("ranking_membership_grant")+" where ranking_id=? and user_id=? and capability='READ'",rank,member.id());
    assertNotFound(list(rank,member,""));grant(rank,member,"READ");assertItems(list(rank,member,""),entry);
    jdbc.update("update "+table("ranking_membership")+" set active=false where ranking_id=? and user_id=?",rank,member.id());
    assertNotFound(list(rank,member,""));
    assertItems(list(rank,owner,""),entry);
    jdbc.update("update "+table("ranking")+" set archived_at=now() where id=?",rank);
    assertItems(list(rank,owner,""),entry);
  }

  @Test void missingPrivatePublicAndAdminWithoutMembershipHaveUniformNonEnumeratingDenials() throws Exception {
    var owner=user(false);var stranger=user(false);var admin=user(true);UUID rank=ranking(owner,"PUBLIC");
    entry(rank,movie(owner),null);
    var denied=list(rank,stranger,"");var missing=list(UUID.randomUUID(),stranger,"");
    assertNotFound(denied);assertNotFound(missing);assertNotFound(list(rank,admin,""));
    assertThat(denied.body()).isEqualTo(missing.body());
    assertThat(denied.body().toString()).doesNotContain(owner.id().toString(),owner.email(),rank.toString());
    var forged=request(path(rank),stranger.token(),Map.of("X-User-Id",owner.id().toString(),"X-Ranking-Role","OWNER"));
    assertNotFound(forged);
    assertThat(request(path(rank),null,Map.of()).status()).isIn(401,403);
    assertThat(request(path(rank),"broken-token",Map.of()).status()).isIn(401,403);
    assertThat(list(rank,owner,"").status()).isEqualTo(200);
    jdbc.update("update "+table("user_statistics")+" set consecutive_failed_login_attempts=10 where user_id=?",owner.id());
    assertThat(request(path(rank),owner.token(),Map.of()).status()).isIn(401,403);
  }

  private static String path(UUID rank) { return "/rankings/"+rank+"/entries"; }
  private ApiResponse list(UUID rank,Account actor,String query) throws Exception {
    var result=request(path(rank)+query,actor.token(),Map.of());
    assertThat(result.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    return result;
  }
  private void assertNotFound(ApiResponse result) {
    assertThat(result.status()).isEqualTo(404);
    assertThat(fields(result.body())).containsExactly("code");
    assertThat(result.body().required("code").asText()).isEqualTo("ranking.not_found");
  }
  private void assertItems(ApiResponse result,UUID...ids) {
    assertThat(result.status()).isEqualTo(200);
    assertThat(fields(result.body())).containsExactlyInAnyOrder("items","next_cursor");
    assertThat(itemIds(result)).containsExactlyElementsOf(java.util.Arrays.stream(ids).map(UUID::toString).toList());
    assertThat(result.body().required("next_cursor").isNull()).isTrue();
  }
  private static List<String> itemIds(ApiResponse result) {
    var ids=new ArrayList<String>();result.body().required("items").forEach(item -> ids.add(item.required("entry_id").asText()));return ids;
  }
  private static UUID orderedId(int value) { return UUID.fromString("10000000-0000-0000-0000-"+String.format("%012d",value)); }
  private UUID movie(Account owner) {
    UUID id=UUID.randomUUID();
    long tmdb=jdbc.queryForObject("select count(*)+1 from "+table("movie"),Long.class);
    jdbc.update("insert into "+table("movie")+" (id,created_at,description,director,original_title,english_title,portuguese_title,user_id,tmdb_id,url_image,release_date,runtime) values (?,now(),'Synthetic description','Synthetic director','Synthetic original','Synthetic English','Sintético português',?,?,'https://example.invalid/poster.jpg','2020-01-02',123)",id,owner.id(),tmdb);return id;
  }
  private UUID entry(UUID rank,UUID movie,UUID author) { UUID id=UUID.randomUUID();entry(rank,id,movie,author);return id; }
  private void entry(UUID rank,UUID id,UUID movie,UUID author) {
    jdbc.update("insert into "+table("ranking_movie_entry")+" (ranking_id,entry_id,movie_id,added_by_author_id,created_at) values (?,?,?,?,'2024-01-02T03:04:05Z')",rank,id,movie,author);
  }
  private UUID author(UUID rank,Account account) {
    UUID id=UUID.randomUUID();jdbc.update("insert into "+table("ranking_author_identity")+" (ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",rank,id,account.id(),UUID.randomUUID());return id;
  }
  private UUID round(UUID rank,UUID entry,long number,Boolean revealed) {
    UUID id=UUID.randomUUID();jdbc.update("insert into "+table("ranking_movie_round")+" (ranking_id,entry_id,round_id,round_number,created_at,revealed) values (?,?,?,?,now(),?)",rank,entry,id,number,revealed);return id;
  }
  private void rating(UUID rank,UUID round,UUID author,Integer score) {
    jdbc.update("insert into "+table("ranking_movie_rating")+" (ranking_id,round_id,author_id,score,created_at) values (?,?,?,?,now())",rank,round,author,score);
  }
  private List<String> allRows() {
    var rows=new ArrayList<String>();
    for(String name:List.of("movie","ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating"))
      rows.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table(name)+" t order by row_to_json(t)::text",String.class));
    return rows;
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
