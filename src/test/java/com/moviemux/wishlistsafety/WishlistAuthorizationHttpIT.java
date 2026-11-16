package com.moviemux.wishlistsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real HTTP authentication, application transactions and PostgreSQL; only providers are stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WishlistAuthorizationHttpIT {
  private static final String SCHEMA =
      "wishlist_auth_test_" + UUID.randomUUID().toString().replace("-", "");
  private static final String PASSWORD = "SyntheticPassword1!";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> OUTBOUND = new CopyOnWriteArrayList<>();
  private static final List<String> UNEXPECTED_OUTBOUND = new CopyOnWriteArrayList<>();
  private static String databaseUrl, databaseUser, databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalServices;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @DynamicPropertySource
  static void isolatedPostgresAndLocalHttp(DynamicPropertyRegistry properties) throws Exception {
    databaseUrl = System.getenv("TEST_DATABASE_URL");
    if (databaseUrl == null
        || !databaseUrl.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
      throw new IllegalStateException(
          "Wishlist HTTP tests require a disposable loopback PostgreSQL test database");
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
    properties.add(
        "spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '10s'");

    externalServices = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    externalServices.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String method = exchange.getRequestMethod();
          OUTBOUND.add(method + " " + path);
          exchange.getRequestBody().readAllBytes();
          String response = "{}";
          int status = 404;
          if (method.equals("GET") && path.matches("/api/v1/tmdb/movie/9[0-9]{5}/summary")) {
            long tmdbId = Long.parseLong(path.split("/")[5]);
            response =
                """
                {"tmdb_id":%d,"imdb_id":"tt%d","portuguese_title":"Filme sintético %d",
                 "english_title":"Synthetic film %d","original_title":"Synthetic film",
                 "director":"Synthetic director","url_image_portuguese":"/synthetic.png",
                 "url_image_english":"/synthetic.png","description":"Local fixture only",
                 "genres":[],"release_date":"2024-01-01","runtime":105}
                """
                    .formatted(tmdbId, tmdbId, tmdbId, tmdbId);
            status = 200;
          }
          if (status == 404) UNEXPECTED_OUTBOUND.add(method + " " + path);
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    externalServices.start();
    String stubUrl = "http://127.0.0.1:" + externalServices.getAddress().getPort();
    properties.add("kit.url", () -> stubUrl);
    properties.add("socket.io.api.url", () -> stubUrl);
    properties.add("discord.webhook.url", () -> stubUrl);
    properties.add("scheduling.enabled", () -> false);
    properties.add("send.mail", () -> false);
    properties.add("discord.webhook.execute", () -> false);
    properties.add("discord.webhook.update", () -> false);
  }

  @AfterAll
  static void releaseOnlyOwnedResources() throws Exception {
    try {
      if (ownsSchema) {
        try (var connection =
                DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
            var statement = connection.createStatement()) {
          statement.execute("drop schema " + SCHEMA + " cascade");
        }
      }
    } finally {
      if (externalServices != null) externalServices.stop(0);
    }
  }

  @BeforeEach
  void cleanOnlyThisTestsSchema() {
    jdbc.execute(
        "truncate table "
            + SCHEMA
            + ".\"user\", "
            + SCHEMA
            + ".movie, "
            + SCHEMA
            + ".movie_wishlist, "
            + SCHEMA
            + ".user_invites restart identity cascade");
    OUTBOUND.clear();
    UNEXPECTED_OUTBOUND.clear();
  }

  @ParameterizedTest(name = "shareable={0}: another user cannot mutate an owner's wishlist")
  @ValueSource(booleans = {false, true})
  void anotherUserCannotMutatePrivateOrSharedWishlistButItsOwnerCan(boolean shared)
      throws Exception {
    Actors actors = registerTwoUsers();
    String wishlist = createWishlist("Owner wishlist", actors.ownerToken());
    addMovie(wishlist, 900001, actors.ownerToken(), 200);
    update(
        wishlist, wishlist, "Owner wishlist", shared, List.of(900001L), actors.ownerToken(), 200);
    JsonNode visible =
        get("/user/wishlist/" + wishlist, actors.outsiderToken(), shared ? 200 : 204);
    if (shared) {
      assertThat(visible.required("user").required("id").asText()).isEqualTo(actors.ownerId());
      assertThat(visible.required("movies_wishlists").size()).isEqualTo(1);
    }
    Snapshot before = snapshot();

    // The legacy response shapes are intentional: PATCH/PUT reject with 400, DELETE is a no-op.
    assertBusinessError(
        addMovie(wishlist, 900099, actors.outsiderToken(), 400), "wishlist not found");
    assertThat(snapshot()).isEqualTo(before);
    assertBusinessError(
        update(
            wishlist,
            wishlist,
            "Outsider replacement",
            !shared,
            List.of(900098L),
            actors.outsiderToken(),
            400),
        "user not authorized to do this action");
    assertThat(snapshot()).isEqualTo(before);
    json("DELETE", "/user/wishlist/" + wishlist + "/delete", null, actors.outsiderToken(), 200);
    assertThat(snapshot()).isEqualTo(before);

    JsonNode added = addMovie(wishlist, 900002, actors.ownerToken(), 200);
    assertThat(added.required("movies_wishlists").size()).isEqualTo(2);
    JsonNode updated =
        update(
            wishlist,
            wishlist,
            "Owner replacement",
            shared,
            List.of(900003L, 900001L),
            actors.ownerToken(),
            200);
    assertThat(updated.required("name").asText()).isEqualTo("Owner replacement");
    assertThat(updated.required("user").required("id").asText()).isEqualTo(actors.ownerId());
    assertThat(updated.required("shareable").asBoolean()).isEqualTo(shared);
    assertThat(updated.required("movies_wishlists").size()).isEqualTo(2);
    assertThat(storedMovieIds(wishlist)).containsExactly(900003L, 900001L);
    assertThat(OUTBOUND)
        .containsExactly(
            "GET /api/v1/tmdb/movie/900001/summary",
            "GET /api/v1/tmdb/movie/900002/summary",
            "GET /api/v1/tmdb/movie/900003/summary");

    json("DELETE", "/user/wishlist/" + wishlist + "/delete", null, actors.ownerToken(), 200);
    get("/user/wishlist/" + wishlist, actors.ownerToken(), 204);
    assertThat(storedMovieIds(wishlist)).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from user_wishlist", Integer.class)).isZero();
    assertThat(UNEXPECTED_OUTBOUND).isEmpty();
  }

  @Test
  void differingUpdatePathAndBodyIdsCannotChangeEitherOwnedWishlistOrCallCatalog()
      throws Exception {
    Actors actors = registerTwoUsers();
    String addressed = createWishlist("Addressed wishlist", actors.ownerToken());
    String bodyTarget = createWishlist("Body target wishlist", actors.ownerToken());
    addMovie(addressed, 900001, actors.ownerToken(), 200);
    addMovie(bodyTarget, 900002, actors.ownerToken(), 200);
    Snapshot before = snapshot();

    assertBusinessError(
        update(
            addressed,
            bodyTarget,
            "Mismatched replacement",
            true,
            List.of(900099L),
            actors.ownerToken(),
            400),
        "wishlist not found");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(storedMovieIds(addressed)).containsExactly(900001L);
    assertThat(storedMovieIds(bodyTarget)).containsExactly(900002L);
    assertThat(UNEXPECTED_OUTBOUND).isEmpty();
  }

  @Test
  void incomingOneHundredItemsAreAllowedButOneHundredAndOneAndAddAtCapacityHaveNoEffects()
      throws Exception {
    Actors actors = registerTwoUsers();
    String wishlist = createWishlist("Boundary wishlist", actors.ownerToken());
    List<Long> oneHundred = LongStream.rangeClosed(900001, 900100).boxed().toList();
    List<Long> oneHundredAndOne = LongStream.rangeClosed(900001, 900101).boxed().toList();
    Snapshot empty = snapshot();

    assertBusinessError(
        update(
            wishlist,
            wishlist,
            "Oversized replacement",
            true,
            oneHundredAndOne,
            actors.ownerToken(),
            400),
        "wishlist not found");
    assertThat(snapshot()).isEqualTo(empty);
    JsonNode accepted =
        update(
            wishlist, wishlist, "Exactly one hundred", false, oneHundred, actors.ownerToken(), 200);
    assertThat(accepted.required("movies_wishlists").size()).isEqualTo(100);
    assertThat(storedMovieIds(wishlist)).containsExactlyElementsOf(oneHundred);
    assertThat(OUTBOUND).hasSize(100);
    Snapshot full = snapshot();

    assertBusinessError(addMovie(wishlist, 900101, actors.ownerToken(), 400), "wishlist not found");
    assertThat(snapshot()).isEqualTo(full);
    update(
        wishlist,
        wishlist,
        "Oversized replacement",
        true,
        oneHundredAndOne,
        actors.ownerToken(),
        400);
    assertThat(snapshot()).isEqualTo(full);

    List<Long> replacementAtCapacity =
        oneHundredAndOne.stream().filter(id -> id != 900100L).toList();
    update(
        wishlist,
        wishlist,
        "Replace at capacity",
        false,
        replacementAtCapacity,
        actors.ownerToken(),
        200);
    assertThat(storedMovieIds(wishlist)).containsExactlyElementsOf(replacementAtCapacity);
    assertThat(OUTBOUND).hasSize(101);

    update(
        wishlist,
        wishlist,
        "Ninety nine items",
        false,
        oneHundred.subList(0, 99),
        actors.ownerToken(),
        200);
    assertThat(storedMovieIds(wishlist)).hasSize(99);
    assertThat(
            addMovie(wishlist, 900102, actors.ownerToken(), 200)
                .required("movies_wishlists")
                .size())
        .isEqualTo(100);
    assertThat(storedMovieIds(wishlist))
        .hasSize(100)
        .contains(900102L)
        .doesNotContain(900100L, 900101L);
    assertThat(OUTBOUND).hasSize(102);
    assertThat(UNEXPECTED_OUTBOUND).isEmpty();
  }

  private record Actors(String ownerId, String ownerToken, String outsiderToken) {}

  private Actors registerTwoUsers() throws Exception {
    register("Synthetic outsider", "outsider@example.test", "");
    String outsiderToken = login("outsider@example.test");
    String invite =
        json("POST", "/restricted/user/invite", null, outsiderToken, 200).required("code").asText();
    JsonNode owner = register("Synthetic owner", "owner@example.test", invite);
    // The legitimate owner is an ordinary user. The unrelated administrator is still not an owner.
    assertThat(owner.required("roles").toString()).isEqualTo("[\"USER\"]");
    return new Actors(owner.required("id").asText(), login("owner@example.test"), outsiderToken);
  }

  private JsonNode register(String name, String email, String invite) throws Exception {
    return json(
        "POST",
        "/public/user",
        Map.of(
            "name", name,
            "email", email,
            "password", PASSWORD,
            "notify", false,
            "invite_code", invite),
        null,
        200);
  }

  private String login(String email) throws Exception {
    return json("POST", "/login", Map.of("email", email, "password", PASSWORD), null, 200)
        .required("access_token")
        .asText();
  }

  private String createWishlist(String name, String token) throws Exception {
    return form("POST", "/user/wishlist", Map.of("name", name), token, 200).required("id").asText();
  }

  private JsonNode addMovie(String wishlist, long tmdbId, String token, int status)
      throws Exception {
    return form(
        "PATCH",
        "/user/wishlist/" + wishlist,
        Map.of("tmdbId", Long.toString(tmdbId)),
        token,
        status);
  }

  private JsonNode update(
      String pathId,
      String bodyId,
      String name,
      boolean shared,
      List<Long> movies,
      String token,
      int status)
      throws Exception {
    return json(
        "PUT",
        "/user/wishlist/" + pathId + "/update",
        Map.of(
            "id", bodyId,
            "name", name,
            "shareable", shared,
            "movies_wishlists", movies.stream().map(id -> Map.of("tmdb_id", id)).toList()),
        token,
        status);
  }

  private void assertBusinessError(JsonNode error, String message) {
    assertThat(error.required("error").asBoolean()).isTrue();
    assertThat(error.required("status").asInt()).isEqualTo(400);
    assertThat(error.required("message").asText()).isEqualTo(message);
  }

  private List<Long> storedMovieIds(String wishlist) {
    return jdbc.queryForList(
        "select movie_id from movie_wishlist_user_wishlist where wishlist_id=? order by"
            + " wishlist_order",
        Long.class,
        UUID.fromString(wishlist));
  }

  private record Snapshot(Map<String, List<Map<String, Object>>> rows, List<String> outbound) {}

  private Snapshot snapshot() {
    Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
    rows.put("wishlists", jdbc.queryForList("select * from user_wishlist order by id"));
    rows.put("catalog", jdbc.queryForList("select * from movie_wishlist order by tmdb_id"));
    rows.put(
        "memberships",
        jdbc.queryForList(
            "select * from movie_wishlist_user_wishlist order by wishlist_id, movie_id"));
    return new Snapshot(rows, List.copyOf(OUTBOUND));
  }

  private JsonNode get(String path, String token, int expectedStatus) throws Exception {
    return request("GET", path, "", "application/json", token, expectedStatus);
  }

  private JsonNode json(String method, String path, Object body, String token, int expectedStatus)
      throws Exception {
    return request(
        method,
        path,
        body == null ? "" : JSON.writeValueAsString(body),
        "application/json",
        token,
        expectedStatus);
  }

  private JsonNode form(
      String method, String path, Map<String, String> body, String token, int expectedStatus)
      throws Exception {
    String encoded =
        body.entrySet().stream()
            .map(
                entry ->
                    URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return request(
        method, path, encoded, "application/x-www-form-urlencoded", token, expectedStatus);
  }

  private JsonNode request(
      String method, String path, String body, String contentType, String token, int expectedStatus)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/monolith" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) request.header("Authorization", "Bearer " + token);
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .as("%s %s response: %s", method, path, response.body())
        .isEqualTo(expectedStatus);
    if (expectedStatus == 204) assertThat(response.body()).isEmpty();
    if (response.body().isBlank()) return NullNode.getInstance();
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    return JSON.readTree(response.body());
  }
}
