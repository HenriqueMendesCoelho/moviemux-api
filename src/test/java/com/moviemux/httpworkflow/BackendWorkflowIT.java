package com.moviemux.httpworkflow;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Real HTTP, security filters, transactions, Flyway and PostgreSQL. Only outbound services are
 * loopback HTTP stubs. No gateway, application mocks, production data or test transaction is used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendWorkflowIT {
  private static final String SCHEMA =
      "http_workflow_test_" + UUID.randomUUID().toString().replace("-", "");
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
          "HTTP workflow requires an explicit disposable loopback PostgreSQL test" + " database");
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
          if (method.equals("GET") && path.matches("/api/v1/tmdb/movie/90000[12]/summary")) {
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
          } else if (method.equals("POST")
              && path.matches("/api/v1/movie/(all/emit/update|[a-f0-9-]+/note/emit/[a-z-]+)")) {
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

  @Test
  void signupLoginRolesMoviesRatingsWishlistsAndAccountRemovalKeepTheirHttpContracts()
      throws Exception {
    get("/movie", null, 403);
    JsonNode admin = register("Synthetic Admin", "admin@example.test", "", 200);
    String adminId = admin.required("id").asText();
    assertThat(strings(admin.required("roles"))).containsExactlyInAnyOrder("USER", "ADM");
    assertThat(admin.has("password")).isFalse();
    assertThat(admin.hasNonNull("created_at")).isTrue();
    json(
        "POST",
        "/login",
        Map.of("email", "admin@example.test", "password", "WrongPassword1!"),
        null,
        401);
    String adminToken = login("admin@example.test", PASSWORD);
    Map<String, Long> accountsBeforeForgedToken = accountRowCounts();
    Long moviesBeforeForgedToken =
        jdbc.queryForObject("select count(*) from " + SCHEMA + ".movie", Long.class);
    List<String> effectsBeforeForgedToken = List.copyOf(OUTBOUND);
    json(
        "POST",
        "/movie",
        movie("Rejected forged token", 900099, 95),
        withCorruptedSignature(adminToken),
        403);
    assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".movie", Long.class))
        .isEqualTo(moviesBeforeForgedToken);
    assertThat(accountRowCounts()).isEqualTo(accountsBeforeForgedToken);
    assertThat(OUTBOUND).containsExactlyElementsOf(effectsBeforeForgedToken);
    assertThat(get("/user", adminToken, 200).required("id").asText()).isEqualTo(adminId);
    get("/movie", adminToken, 204);
    get("/movie/genres", adminToken, 204);

    register("No Invite User", "no-invite@example.test", "invalid", 400);
    String invite =
        json("POST", "/restricted/user/invite", null, adminToken, 200).required("code").asText();
    assertThat(get("/restricted/user/invite", adminToken, 200).size()).isEqualTo(1);
    JsonNode member = register("Synthetic Member", "member@example.test", invite, 200);
    String memberId = member.required("id").asText();
    assertThat(strings(member.required("roles"))).containsExactly("USER");
    assertThat(get("/restricted/user/invite", adminToken, 200).size()).isZero();
    register("Reused Invite", "reused@example.test", invite, 400);
    String memberToken = login("member@example.test", PASSWORD);
    assertLegacyMethodSecurityDenial("GET", "/restricted/user", memberToken);
    assertLegacyMethodSecurityDenial("POST", "/restricted/user/invite", memberToken);
    assertThat(get("/restricted/user/invite", adminToken, 200).size()).isZero();
    JsonNode promoted =
        json("PATCH", "/restricted/user/" + memberId + "/promote", null, adminToken, 200);
    assertThat(strings(promoted.required("roles"))).contains("ADM");
    String promotedToken = login("member@example.test", PASSWORD);
    assertThat(get("/restricted/user", promotedToken, 200).size()).isEqualTo(2);
    JsonNode demoted =
        json("PATCH", "/restricted/user/" + memberId + "/demote", null, adminToken, 200);
    assertThat(strings(demoted.required("roles"))).containsExactly("USER");
    assertLegacyMethodSecurityDenial("GET", "/restricted/user", promotedToken);
    memberToken = login("member@example.test", PASSWORD);

    JsonNode updatedProfile =
        form(
            "PATCH",
            "/user/update",
            Map.of("name", "Updated Member", "email", "member@example.test", "notify", "false"),
            memberToken,
            200);
    assertThat(updatedProfile.required("name").asText()).isEqualTo("Updated Member");
    assertThat(updatedProfile.required("preferences").required("notify").asBoolean()).isFalse();
    form(
        "PATCH",
        "/user/p/update",
        Map.of("password", PASSWORD, "newPassword", "UpdatedPassword2!"),
        memberToken,
        200);
    memberToken = login("member@example.test", "UpdatedPassword2!");

    Map<String, Object> adminMovieInput = movie("Filme do administrador", 900001, 0);
    JsonNode adminMovie = json("POST", "/movie", adminMovieInput, adminToken, 200);
    String adminMovieId = adminMovie.required("id").asText();
    assertThat(adminMovie.required("user_id").asText()).isEqualTo(adminId);
    assertThat(adminMovie.required("portuguese_title").asText())
        .isEqualTo("Filme do administrador");
    assertThat(adminMovie.has("portugueseTitle")).isFalse();
    assertThat(adminMovie.required("runtime").asInt()).isZero();
    json("POST", "/movie", adminMovieInput, adminToken, 400);
    adminMovieInput.put("runtime", 95);
    JsonNode edited =
        json("PUT", "/movie/" + adminMovieId + "/update", adminMovieInput, adminToken, 200);
    assertThat(edited.required("runtime").asInt()).isEqualTo(95);
    assertThat(get("/movie/" + adminMovieId, memberToken, 200).required("runtime").asInt())
        .isEqualTo(95);
    json("PUT", "/movie/" + adminMovieId + "/update", adminMovieInput, memberToken, 403);
    json("DELETE", "/movie/" + adminMovieId + "/delete", null, memberToken, 403);

    JsonNode memberMovie =
        json("POST", "/movie", movie("Filme preservado", 900002, 105), memberToken, 200);
    String memberMovieId = memberMovie.required("id").asText();
    assertThat(memberMovie.required("user_id").asText()).isEqualTo(memberId);
    JsonNode page = get("/movie?page=1&size=1&sort=portugueseTitle,asc", adminToken, 200);
    assertThat(page.required("content").size()).isEqualTo(1);
    assertThat(page.required("total_elements").asInt()).isEqualTo(2);
    assertThat(page.required("number").asInt()).isZero();
    assertThat(
            get("/movie?title=preservado", adminToken, 200)
                .required("content")
                .get(0)
                .required("id")
                .asText())
        .isEqualTo(memberMovieId);

    JsonNode zeroNote = rate(adminMovieId, 0, adminToken);
    assertThat(zeroNote.required("note").asInt()).isZero();
    assertThat(zeroNote.required("user").required("id").asText()).isEqualTo(adminId);
    json("POST", "/movie/note", Map.of("movie_id", adminMovieId, "note", 1), adminToken, 400);
    json("POST", "/movie/note", Map.of("movie_id", memberMovieId, "note", 11), memberToken, 400);
    JsonNode editedNote =
        json(
            "PATCH",
            "/movie/note/" + adminMovieId + "/update",
            Map.of("movie_id", adminMovieId, "note", 8),
            adminToken,
            200);
    assertThat(editedNote.required("note").asInt()).isEqualTo(8);
    assertThat(get("/movie/note/" + adminMovieId, memberToken, 200).get(0).required("note").asInt())
        .isEqualTo(8);
    json("DELETE", "/movie/note/" + adminMovieId + "/delete", null, adminToken, 200);
    assertThat(get("/movie/note/" + adminMovieId, adminToken, 200).size()).isZero();
    rate(adminMovieId, 8, adminToken);
    rate(memberMovieId, 8, adminToken);
    rate(memberMovieId, 6, memberToken);

    String adminWishlistId = createWishlist("Admin watchlist", adminToken);
    String memberWishlistId = createWishlist("Member watchlist", memberToken);
    get("/user/wishlist/" + memberWishlistId, adminToken, 204);
    JsonNode renamed =
        json(
            "PUT",
            "/user/wishlist/" + memberWishlistId + "/update",
            Map.of(
                "id",
                memberWishlistId,
                "name",
                "Shared watchlist",
                "shareable",
                true,
                "movies_wishlists",
                List.of()),
            memberToken,
            200);
    assertThat(renamed.required("name").asText()).isEqualTo("Shared watchlist");
    assertThat(renamed.required("shareable").asBoolean()).isTrue();
    form("PATCH", "/user/wishlist/" + adminWishlistId, Map.of("tmdbId", "900001"), adminToken, 200);
    form(
        "PATCH",
        "/user/wishlist/" + memberWishlistId,
        Map.of("tmdbId", "900002"),
        memberToken,
        200);
    JsonNode sharedWishlist = get("/user/wishlist/" + memberWishlistId, adminToken, 200);
    assertThat(sharedWishlist.required("user").required("id").asText()).isEqualTo(memberId);
    assertThat(sharedWishlist.required("movies_wishlists").size()).isEqualTo(1);
    assertThat(sharedWishlist.required("movies_wishlists").get(0).required("tmdb_id").asLong())
        .isEqualTo(900002);
    assertThat(sharedWishlist.required("movies_wishlists").get(0).required("title").asText())
        .isEqualTo("Filme sintético 900002");
    assertThat(
            get("/user/wishlist/" + memberWishlistId + "/movies-rated", memberToken, 200)
                .required("movie_tmdb_ids")
                .get(0)
                .asLong())
        .isEqualTo(900002);
    assertThat(get("/user/wishlist", memberToken, 200).size()).isEqualTo(1);

    JsonNode statistics = get("/statistic/movies", adminToken, 200);
    assertThat(statistics.required("total_number_of_movies").asInt()).isEqualTo(2);
    assertThat(statistics.required("average_runtime").asInt()).isEqualTo(100);
    assertThat(statistics.required("average_rate").asDouble()).isEqualTo(7.33);
    assertThat(statistics.required("movies_six_months_ago").size()).isEqualTo(7);
    JsonNode activity = get("/user", memberToken, 200).required("statistics");
    assertThat(activity.required("registered_movies").asInt()).isEqualTo(1);
    assertThat(activity.required("ratings_given").asInt()).isEqualTo(1);
    assertThat(activity.required("average_rating_movies").asDouble()).isEqualTo(6);

    // Removing the owner must leave their movie and other users' ratings intact.
    json("DELETE", "/restricted/user/" + memberId, null, adminToken, 200);
    get("/restricted/user/member@example.test", adminToken, 204);
    assertThat(get("/restricted/user", adminToken, 200).size()).isEqualTo(1);
    JsonNode retained = get("/movie/" + memberMovieId, adminToken, 200);
    assertThat(retained.required("id").asText()).isEqualTo(memberMovieId);
    assertThat(retained.has("user_id")).isFalse();
    assertThat(retained.has("user_name")).isFalse();
    assertThat(retained.required("notes").size()).isEqualTo(1);
    assertThat(retained.required("notes").get(0).required("user").required("id").asText())
        .isEqualTo(adminId);
    assertThat(retained.required("notes").get(0).required("note").asInt()).isEqualTo(8);
    get("/user/wishlist/" + memberWishlistId, adminToken, 204);
    assertThat(get("/user/wishlist", adminToken, 200).size()).isEqualTo(1);
    // Existing account-removal behavior does not evict the statistics cache. Expose the stale
    // response explicitly rather than disabling caching or claiming immediate recomputation.
    assertThat(get("/statistic/movies", adminToken, 200)).isEqualTo(statistics);

    // Administrative CRUD also works after detaching the former owner's scalar foreign key.
    json(
        "PUT",
        "/movie/" + memberMovieId + "/update",
        movie("Filme preservado", 900002, 110),
        adminToken,
        200);
    JsonNode refreshedStatistics = get("/statistic/movies", adminToken, 200);
    assertThat(refreshedStatistics.required("average_rate").asDouble()).isEqualTo(8);
    assertThat(refreshedStatistics.required("average_runtime").asInt()).isEqualTo(102);
    json("DELETE", "/user/wishlist/" + adminWishlistId + "/delete", null, adminToken, 200);
    get("/user/wishlist/" + adminWishlistId, adminToken, 204);
    json("DELETE", "/movie/" + adminMovieId + "/delete", null, adminToken, 200);
    json("DELETE", "/movie/" + memberMovieId + "/delete", null, adminToken, 200);
    get("/movie/" + memberMovieId, adminToken, 204);
    get("/movie", adminToken, 204);
    assertThat(get("/statistic/movies", adminToken, 200).required("total_number_of_movies").asInt())
        .isZero();
    assertThat(OUTBOUND)
        .contains("GET /api/v1/tmdb/movie/900001/summary", "GET /api/v1/tmdb/movie/900002/summary");
    assertThat(OUTBOUND)
        .anyMatch(
            path -> path.equals("POST /api/v1/movie/" + memberMovieId + "/note/emit/create-note"));
    assertThat(OUTBOUND).noneMatch(path -> path.contains("sendgrid") || path.contains("webhook"));
    assertThat(UNEXPECTED_OUTBOUND)
        .as("only the explicit catalog/socket stub routes may be used")
        .isEmpty();
  }

  @Test
  void realSignupCommitFailureRollsBackAccountRowsAndRestoresConsumedInvite() throws Exception {
    register("Commit Admin", "commit-admin@example.test", "", 200);
    String adminToken = login("commit-admin@example.test", PASSWORD);
    String invite =
        json("POST", "/restricted/user/invite", null, adminToken, 200).required("code").asText();
    String rejectedEmail = "commit-rollback@example.test";
    Map<String, Long> before = accountRowCounts();

    try {
      jdbc.execute("create sequence " + SCHEMA + ".http_signup_commit_probe");
      jdbc.execute(
          """
          create function %1$s.reject_http_signup_commit() returns trigger
          language plpgsql as $fixture$
          begin
            if not exists (select 1 from %1$s.user_preferences where user_id = new.id)
                or not exists (select 1 from %1$s.user_statistics where user_id = new.id)
                or not exists (select 1 from %1$s.user_roles where user_id = new.id)
                or exists (select 1 from %1$s.user_invites where code = '%2$s') then
              raise exception 'Synthetic commit fixture did not observe all application writes';
            end if;
            perform nextval('%1$s.http_signup_commit_probe');
            raise exception 'Synthetic deferred signup commit failure' using errcode = '23514';
          end;
          $fixture$
          """
              .formatted(SCHEMA, invite.replace("'", "''")));
      jdbc.execute(
          """
          create constraint trigger http_signup_commit_guard
          after insert on %s."user" deferrable initially deferred
          for each row when (new.email = 'commit-rollback@example.test')
          execute function %s.reject_http_signup_commit()
          """
              .formatted(SCHEMA, SCHEMA));
      assertThat(
              jdbc.queryForObject(
                  "select tgdeferrable and tginitdeferred from pg_trigger"
                      + " where tgname='http_signup_commit_guard'"
                      + " and tgrelid=?::regclass",
                  Boolean.class,
                  SCHEMA + ".\"user\""))
          .isTrue();

      // This invokes the application controller and transactional registration use case over HTTP.
      JsonNode error = register("Commit Rollback Member", rejectedEmail, invite, 500);
      assertSanitizedInternalError(error, "/public/user");

      // PostgreSQL sequences are not rolled back. This marker is advanced only by the deferred
      // trigger after it observed preferences/statistics/roles and the consumed invite together.
      assertThat(
              jdbc.queryForObject(
                  "select is_called from " + SCHEMA + ".http_signup_commit_probe", Boolean.class))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select last_value from " + SCHEMA + ".http_signup_commit_probe", Long.class))
          .isEqualTo(1L);
      assertThat(accountRowCounts()).isEqualTo(before);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + SCHEMA + ".\"user\" where email=?",
                  Long.class,
                  rejectedEmail))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from " + SCHEMA + ".user_invites where code=?",
                  Long.class,
                  invite))
          .isEqualTo(1L);
      get("/restricted/user/" + rejectedEmail, adminToken, 204);
      assertThat(get("/restricted/user", adminToken, 200).size()).isEqualTo(1);
      JsonNode invitations = get("/restricted/user/invite", adminToken, 200);
      assertThat(invitations.size()).isEqualTo(1);
      assertThat(invitations.get(0).required("code").asText()).isEqualTo(invite);
      // Email is disabled in this profile; this scenario proves HTTP/data atomicity, not delivery.
    } finally {
      jdbc.execute("drop trigger if exists http_signup_commit_guard on " + SCHEMA + ".\"user\"");
      jdbc.execute("drop function if exists " + SCHEMA + ".reject_http_signup_commit()");
      jdbc.execute("drop sequence if exists " + SCHEMA + ".http_signup_commit_probe");
    }

    // The same request and invitation succeed once only the synthetic commit failure is removed.
    JsonNode recovered = register("Commit Rollback Member", rejectedEmail, invite, 200);
    assertThat(recovered.required("email").asText()).isEqualTo(rejectedEmail);
    String memberToken = login(rejectedEmail, PASSWORD);
    assertThat(get("/user", memberToken, 200).required("id").asText())
        .isEqualTo(recovered.required("id").asText());
    assertThat(get("/restricted/user/invite", adminToken, 200).size()).isZero();
    Map<String, Long> afterRetry = accountRowCounts();
    assertThat(afterRetry.get("\"user\"")).isEqualTo(before.get("\"user\"") + 1);
    assertThat(afterRetry.get("user_preferences")).isEqualTo(before.get("user_preferences") + 1);
    assertThat(afterRetry.get("user_statistics")).isEqualTo(before.get("user_statistics") + 1);
    assertThat(afterRetry.get("user_roles")).isEqualTo(before.get("user_roles") + 1);
  }

  private Map<String, Long> accountRowCounts() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String table : List.of("\"user\"", "user_preferences", "user_statistics", "user_roles")) {
      counts.put(
          table, jdbc.queryForObject("select count(*) from " + SCHEMA + "." + table, Long.class));
    }
    return counts;
  }

  @Test
  void nonAdminOwnerCanUpdateAndDeleteOwnMovieInCurrentHttpPersistenceContext() throws Exception {
    register("Synthetic Admin", "owner-admin@example.test", "", 200);
    String adminToken = login("owner-admin@example.test", PASSWORD);
    String invite =
        json("POST", "/restricted/user/invite", null, adminToken, 200).required("code").asText();
    JsonNode owner = register("Synthetic Owner", "owner@example.test", invite, 200);
    String ownerToken = login("owner@example.test", PASSWORD);
    Map<String, Object> input = movie("Owner UUID characterization", 900001, 90);
    JsonNode created = json("POST", "/movie", input, ownerToken, 200);
    String movieId = created.required("id").asText();
    assertThat(created.required("user_id").asText()).isEqualTo(owner.required("id").asText());
    input.put("runtime", 91);

    // Real HTTP confirms the current request/persistence context permits this owner flow.
    // It does not invalidate the separate unit characterization of UUID reference comparison.
    json("PUT", "/movie/" + movieId + "/update", input, ownerToken, 200);
    assertThat(get("/movie/" + movieId, ownerToken, 200).required("runtime").asInt()).isEqualTo(91);
    json("DELETE", "/movie/" + movieId + "/delete", null, ownerToken, 200);
    get("/movie/" + movieId, ownerToken, 204);
  }

  private void assertLegacyMethodSecurityDenial(String method, String path, String token)
      throws Exception {
    // Existing bug: the catch-all advice turns AuthorizationDeniedException into HTTP 500.
    // Preserve and expose the observed response; do not claim the expected HTTP 403 is working.
    JsonNode denial = json(method, path, null, token, 500);
    assertSanitizedInternalError(denial, path);
  }

  private void assertSanitizedInternalError(JsonNode error, String path) {
    assertThat(error.required("status").asText()).isEqualTo("500");
    assertThat(error.required("error").asText()).isEqualTo("internal server error");
    assertThat(error.required("message").asText()).isEqualTo("contact the administrator");
    assertThat(error.required("path").asText()).isEqualTo("/api/monolith" + path);
    List<String> fields = new ArrayList<>();
    error.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
  }

  private JsonNode register(String name, String email, String invite, int status) throws Exception {
    return json(
        "POST",
        "/public/user",
        Map.of(
            "name",
            name,
            "email",
            email,
            "password",
            PASSWORD,
            "notify",
            false,
            "invite_code",
            invite),
        null,
        status);
  }

  private String login(String email, String password) throws Exception {
    JsonNode response =
        json("POST", "/login", Map.of("email", email, "password", password), null, 200);
    assertThat(response.hasNonNull("expires")).isTrue();
    assertThat(response.has("accessToken")).isFalse();
    String token = response.required("access_token").asText();
    assertThat(token).isNotBlank();
    return token;
  }

  private static String withCorruptedSignature(String token) {
    String[] parts = token.split("\\.");
    assertThat(parts).hasSize(3);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    assertThat(signature).isNotEmpty();
    // Flip actual signature bytes, rather than changing an unused Base64 padding bit.
    signature[0] ^= 1;
    String corruptedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    assertThat(Base64.getUrlDecoder().decode(corruptedSignature))
        .isNotEqualTo(Base64.getUrlDecoder().decode(parts[2]));
    return parts[0] + "." + parts[1] + "." + corruptedSignature;
  }

  private JsonNode rate(String movieId, int note, String token) throws Exception {
    return json("POST", "/movie/note", Map.of("movie_id", movieId, "note", note), token, 200);
  }

  private String createWishlist(String name, String token) throws Exception {
    return form("POST", "/user/wishlist", Map.of("name", name), token, 200).required("id").asText();
  }

  private static Map<String, Object> movie(String title, long tmdbId, int runtime) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("portuguese_title", title);
    input.put("english_title", title);
    input.put("original_title", title);
    input.put("director", "Synthetic Director");
    input.put("description", "Synthetic local HTTP workflow fixture");
    input.put("url_image", "/synthetic.png");
    input.put("genres", List.of());
    input.put("release_date", "2024-01-01");
    input.put("tmdb_id", tmdbId);
    input.put("runtime", runtime);
    input.put("show_notes", true);
    return input;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
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
            .timeout(Duration.ofSeconds(20))
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
