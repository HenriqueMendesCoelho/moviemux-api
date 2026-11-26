package com.moviemux.userboundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.moviemux.application.spring.security.util.JWTUtil;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/**
 * Registration through real HTTP filters, application transactions and Flyway-managed PostgreSQL.
 * All accounts are synthetic, isolated in an owned schema; no real provider is reachable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationModeHttpIT {
  private static final String SCHEMA =
      "registration_http_test_" + UUID.randomUUID().toString().replace("-", "");
  private static final String PUBLIC_MODE = "/public/registration-mode";
  private static final String ADMIN_MODE = "/restricted/registration-mode";
  private static final String PUBLIC_WAITLIST = "/public/registration-waitlist";
  private static final String ADMIN_WAITLIST = "/restricted/registration-waitlist";
  private static final String SIGNUP = "/public/user";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicInteger OUTBOUND = new AtomicInteger();
  private static String databaseUrl, databaseUser, databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalStub;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JWTUtil jwt;
  @Autowired private DataSource dataSource;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry properties) throws Exception {
    databaseUrl = System.getenv("TEST_DATABASE_URL");
    if (databaseUrl == null || !databaseUrl.matches(
        "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
      throw new IllegalStateException("Registration HTTP tests require disposable loopback PostgreSQL");
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
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });
    externalStub.start();
    String stub = "http://127.0.0.1:" + externalStub.getAddress().getPort();
    for (String name : List.of("kit.url", "mail.url", "socket.io.api.url", "discord.webhook.url")) {
      properties.add(name, () -> stub);
    }
    properties.add("scheduling.enabled", () -> false);
    properties.add("send.mail", () -> false);
    properties.add("discord.webhook.execute", () -> false);
    properties.add("discord.webhook.update", () -> false);
    properties.add("registration.waitlist.invite-template", () -> "");
    properties.add("registration.waitlist.registration-url", () -> "");
  }

  @AfterAll
  static void cleanup() throws Exception {
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
  void cleanOwnedFixtures() {
    // Explicitly reset the single policy only inside this fresh, test-owned schema.
    jdbc.execute("truncate table registration_waitlist,user_invites, \"user\" restart identity cascade");
    jdbc.update("update instance_registration_policy set mode='INVITE_ONLY',bootstrap_completed=false where singleton=true");
    OUTBOUND.set(0);
  }

  @AfterEach
  void noExternalTraffic() {
    assertThat(OUTBOUND.get()).isZero();
  }

  @Test
  void bootstrapIsOneTimeAndLegacyInvitationsStayAtomicAcrossModes() throws Exception {
    assertMode(send("GET", PUBLIC_MODE, null, null), "INVITE_ONLY");
    assertThat(count("instance_registration_policy")).isEqualTo(1);
    assertThat(count("\"user\"")).isZero();
    Account admin = bootstrap();
    String firstCode = legacyCode();
    String visitor = email("visitor");

    assertInviteRequired(send("POST", SIGNUP, null, signup(visitor, null)));
    assertThat(count("\"user\"")).isEqualTo(1);
    assertThat(inviteExists(firstCode)).isTrue();
    var joined = send("POST", SIGNUP, null, signup(visitor, firstCode));
    assertThat(joined.status()).isEqualTo(200);
    assertThat(roles(visitor)).containsExactly(2);
    assertThat(inviteExists(firstCode)).isFalse();
    assertInviteRequired(send("POST", SIGNUP, null, signup(email("another"), firstCode)));

    // Failure after validating an otherwise valid invitation must roll the consumption back.
    String retained = legacyCode();
    assertThat(send("POST", SIGNUP, null, signup(visitor, retained)).status()).isEqualTo(400);
    assertThat(inviteExists(retained)).isTrue();

    setMode(admin, "OPEN");
    String open = email("open");
    assertThat(send("POST", SIGNUP, null, signup(open, null)).status()).isEqualTo(200);
    assertThat(roles(open)).containsExactly(2);
    assertThat(inviteExists(retained)).isTrue();
    String optional = email("optional");
    assertThat(send("POST", SIGNUP, null, signup(optional, retained)).status()).isEqualTo(200);
    assertThat(roles(optional)).containsExactly(2);
    assertThat(inviteExists(retained)).isFalse();
    assertThat(count("ranking_membership")).isZero();

    setMode(admin, "INVITE_ONLY");
    // Deleting all user fixtures must not turn a subsequent public visitor into a new ADM.
    jdbc.execute("truncate table \"user\" restart identity cascade");
    assertThat(jdbc.queryForObject(
        "select bootstrap_completed from instance_registration_policy where singleton=true", Boolean.class))
        .isTrue();
    assertInviteRequired(send("POST", SIGNUP, null, signup(email("post-bootstrap"), null)));
    assertThat(count("\"user\"")).isZero();
  }

  @Test
  void currentGlobalAdminOnlyCanReadOrMutateRegistrationAdministration() throws Exception {
    Account admin = bootstrap();
    Account ordinary = ordinaryAccount(admin);
    assertThat(send("GET", ADMIN_MODE, null, null).status()).isIn(401, 403);
    assertThat(send("PUT", ADMIN_MODE, ordinary.token(), mode("OPEN")).status()).isEqualTo(403);
    assertThat(send("GET", ADMIN_MODE, ordinary.token(), null).status()).isEqualTo(403);
    assertThat(send("GET", ADMIN_WAITLIST, ordinary.token(), null).status()).isEqualTo(403);
    assertThat(send("POST", ADMIN_WAITLIST + "/" + UUID.randomUUID() + "/invite",
        ordinary.token(), null).status()).isEqualTo(403);

    assertMode(send("GET", ADMIN_MODE, admin.token(), null), "INVITE_ONLY");
    setMode(admin, "WAITLIST");
    assertError(send("PUT", ADMIN_MODE, admin.token(), mode("UNKNOWN")),
        400, "registration.invalid_request");
    assertMode(send("GET", PUBLIC_MODE, null, null), "WAITLIST");

    jdbc.update("delete from user_roles where user_id=? and roles=1", admin.id());
    assertThat(send("PUT", ADMIN_MODE, admin.token(), mode("OPEN")).status()).isEqualTo(403);
    assertThat(send("GET", ADMIN_MODE, admin.token(), null).status()).isEqualTo(403);
    assertThat(send("GET", ADMIN_WAITLIST, admin.token(), null).status()).isEqualTo(403);
    assertMode(send("GET", PUBLIC_MODE, null, null), "WAITLIST");

    jdbc.update("insert into user_roles(user_id,roles) values (?,1)", admin.id());
    jdbc.update("update user_statistics set consecutive_failed_login_attempts=10 where user_id=?", admin.id());
    assertThat(send("PUT", ADMIN_MODE, admin.token(), mode("OPEN")).status()).isIn(401,403);
    assertMode(send("GET", PUBLIC_MODE, null, null), "WAITLIST");
    assertThat(count("registration_waitlist")).isZero();
  }

  @Test
  void waitlistNormalizesEmailsAndAnIssuedCodeRemainsBoundAndPreservedUntilSuccessfulSignup()
      throws Exception {
    Account admin = bootstrap();
    assertError(send("POST", PUBLIC_WAITLIST, null, waitlist(email("closed"))),
        409, "registration.mode_changed");
    setMode(admin, "WAITLIST");
    var invalid = send("POST", PUBLIC_WAITLIST, null, waitlist("not-an-email"));
    assertError(invalid, 400, "registration.invalid_request");

    String requested = email("requested");
    String other = email("other");
    Response created = send("POST", PUBLIC_WAITLIST, null, waitlist("  " + requested.toUpperCase(java.util.Locale.ROOT) + "  "));
    Response duplicate = send("POST", PUBLIC_WAITLIST, null, waitlist(requested));
    Response existing = send("POST", PUBLIC_WAITLIST, null, waitlist(admin.email()));
    for (var response : List.of(created, duplicate, existing)) {
      assertNoStore(response, 202);
      assertThat(response.body().isNull()).isTrue();
    }
    assertThat(count("registration_waitlist")).isEqualTo(1);
    assertThat(count("user_invites")).isZero();
    assertThat(count("\"user\"")).isEqualTo(1);
    assertNoStore(send("POST", PUBLIC_WAITLIST, null, waitlist(other)), 202);

    // The admin-only list is bounded and contains no codes; cursor returns the remaining row.
    var firstPage = send("GET", ADMIN_WAITLIST+"?limit=1", admin.token(), null);
    assertNoStore(firstPage, 200);
    assertThat(firstPage.body().path("items").size()).isEqualTo(1);
    assertThat(firstPage.body().path("next_cursor").isTextual()).isTrue();
    var nextPage = send("GET", ADMIN_WAITLIST+"?limit=1&after="+firstPage.body().path("next_cursor").asText(),
        admin.token(), null);
    assertNoStore(nextPage, 200);
    assertThat(nextPage.body().path("items").size()).isEqualTo(1);
    var rows = new ArrayList<JsonNode>();
    firstPage.body().path("items").forEach(rows::add);
    nextPage.body().path("items").forEach(rows::add);
    assertThat(rows.stream().map(row->row.path("email").asText()).toList()).containsExactlyInAnyOrder(requested,other);
    for (var row : rows) {
      assertThat(row.path("status").asText()).isEqualTo("PENDING");
      assertThat(row.path("requested_at").isTextual()).isTrue();
      assertThat(row.has("invite_code")).isFalse();
    }
    var requestId = requestId(rows,requested);
    String path = ADMIN_WAITLIST+"/"+requestId+"/invite";
    var receipt = send("POST",path,admin.token(),null);
    assertNoStore(receipt,200);
    assertThat(receipt.body().path("request_id").asText()).isEqualTo(requestId);
    assertThat(receipt.body().path("status").asText()).isEqualTo("DELIVERY_UNAVAILABLE");
    String code = receipt.body().path("invite_code").asText();
    assertThat(code).matches("KB-[0-9]{8,9}");
    var retry = send("POST",path,admin.token(),null);
    assertNoStore(retry,200);
    assertThat(retry.body()).isEqualTo(receipt.body());
    assertThat(count("user_invites")).isEqualTo(1);

    assertInviteRequired(send("POST", SIGNUP, null, signup(other,code)));
    assertThat(inviteExists(code)).isTrue();
    setMode(admin,"INVITE_ONLY");
    // A valid previously issued waitlist code can complete signup after switching modes.
    assertThat(send("POST",SIGNUP,null,signup(requested,code)).status()).isEqualTo(200);
    assertThat(inviteExists(code)).isFalse();
    assertThat(roles(requested)).containsExactly(2);
    assertThat(count("registration_waitlist")).isEqualTo(1);
    assertThat(count("ranking_membership")).isZero();
    assertError(send("POST",path,admin.token(),null),404,"registration.request_not_found");

    // An explicitly revoked invite is not recreated when a stale admin UI retries.
    var otherId=requestId(rows,other);
    var otherPath=ADMIN_WAITLIST+"/"+otherId+"/invite";
    var otherReceipt=send("POST",otherPath,admin.token(),null);
    assertNoStore(otherReceipt,200);
    String otherCode=otherReceipt.body().path("invite_code").asText();
    assertThat(send("DELETE","/restricted/user/invite/"+otherCode+"/delete",admin.token(),null).status())
        .isEqualTo(200);
    assertThat(inviteExists(otherCode)).isFalse();
    assertError(send("POST",otherPath,admin.token(),null),409,"registration.invitation_revoked");
    assertThat(count("user_invites")).isZero();
  }

  @Test
  void concurrentWaitlistAndSignupProduceOneRequestAndOneAccount() throws Exception {
    Account admin = bootstrap();
    setMode(admin,"WAITLIST");
    String address=email("concurrent");
    var a=http.sendAsync(request("POST",PUBLIC_WAITLIST,null,waitlist(address)),HttpResponse.BodyHandlers.ofString());
    var b=http.sendAsync(request("POST",PUBLIC_WAITLIST,null,waitlist(address)),HttpResponse.BodyHandlers.ofString());
    assertNoStore(response(a.get(15,TimeUnit.SECONDS)),202);
    assertNoStore(response(b.get(15,TimeUnit.SECONDS)),202);
    assertThat(count("registration_waitlist")).isEqualTo(1);

    var page=send("GET",ADMIN_WAITLIST,admin.token(),null);
    String id=page.body().path("items").get(0).path("request_id").asText();
    String path=ADMIN_WAITLIST+"/"+id+"/invite";
    var first=http.sendAsync(request("POST",path,admin.token(),null),HttpResponse.BodyHandlers.ofString());
    var second=http.sendAsync(request("POST",path,admin.token(),null),HttpResponse.BodyHandlers.ofString());
    var results=List.of(response(first.get(15,TimeUnit.SECONDS)),response(second.get(15,TimeUnit.SECONDS)));
    assertThat(results.stream().map(Response::status).toList()).allMatch(status->status==200 || status==409);
    var successful=results.stream().filter(result->result.status()==200).toList();
    assertThat(successful).isNotEmpty();
    var code=successful.getFirst().body().path("invite_code").asText();
    for (var result : successful) {
      assertThat(result.body().path("invite_code").asText()).isEqualTo(code);
    }
    assertThat(count("user_invites")).isEqualTo(1);

    var signupA=http.sendAsync(request("POST",SIGNUP,null,signup(address,code)),HttpResponse.BodyHandlers.ofString());
    var signupB=http.sendAsync(request("POST",SIGNUP,null,signup(address,code)),HttpResponse.BodyHandlers.ofString());
    assertThat(List.of(response(signupA.get(15,TimeUnit.SECONDS)).status(),
        response(signupB.get(15,TimeUnit.SECONDS)).status())).containsExactlyInAnyOrder(200,400);
    assertThat(jdbc.queryForObject("select count(*) from \"user\" where email=?",Long.class,address)).isEqualTo(1);
    assertThat(roles(address)).containsExactly(2);
    assertThat(count("registration_waitlist")).isZero();
    assertThat(count("user_invites")).isZero();
    assertThat(count("ranking_membership")).isZero();
  }

  @Test
  void signupObservesCommittedPolicyAfterWaitingForTheAuthoritativeRow() throws Exception {
    Account admin=bootstrap();
    setMode(admin,"OPEN");
    String address=email("mode-race");
    try(var blocker=dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      int pid;
      try(var statement=blocker.createStatement();
          var rows=statement.executeQuery("select pg_backend_pid()")) {
        assertThat(rows.next()).isTrue();
        pid=rows.getInt(1);
      }
      try(var statement=blocker.createStatement()) {
        assertThat(statement.executeUpdate(
            "update instance_registration_policy set mode='INVITE_ONLY' where singleton=true"))
            .isEqualTo(1);
      }
      var pending=http.sendAsync(request("POST",SIGNUP,null,signup(address,null)),
          HttpResponse.BodyHandlers.ofString());
      try {
        long deadline=System.nanoTime()+Duration.ofSeconds(7).toNanos();
        boolean waited=false;
        while(System.nanoTime()<deadline && !pending.isDone()) {
          waited=jdbc.queryForObject("""
              select exists(select 1 from pg_stat_activity
                where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))
                  and query ilike '%instance_registration_policy%')
              """,Boolean.class,pid);
          if(waited)break;
          Thread.sleep(20);
        }
        assertThat(waited).as("signup transaction waited on persisted policy").isTrue();
      } finally {
        blocker.commit();
      }
      assertInviteRequired(response(pending.get(15,TimeUnit.SECONDS)));
    }
    assertMode(send("GET",PUBLIC_MODE,null,null),"INVITE_ONLY");
    assertThat(jdbc.queryForObject("select count(*) from \"user\" where email=?",Long.class,address))
        .isZero();
  }

  @Test
  void competingFreshInstallationSignupsBootstrapExactlyOneAdministrator() throws Exception {
    String first=email("fresh-first"), second=email("fresh-second");
    var a=http.sendAsync(request("POST",SIGNUP,null,signup(first,null)),HttpResponse.BodyHandlers.ofString());
    var b=http.sendAsync(request("POST",SIGNUP,null,signup(second,null)),HttpResponse.BodyHandlers.ofString());
    assertThat(List.of(response(a.get(15,TimeUnit.SECONDS)).status(),
        response(b.get(15,TimeUnit.SECONDS)).status())).containsExactlyInAnyOrder(200,400);
    assertThat(count("\"user\"")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from user_roles where roles=1",Long.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select bootstrap_completed from instance_registration_policy where singleton=true",Boolean.class))
        .isTrue();
  }

  private Account bootstrap() throws Exception {
    String email=email("admin");
    var response=send("POST",SIGNUP,null,signup(email,null));
    assertThat(response.status()).as("bootstrap response: %s",response.body()).isEqualTo(200);
    assertThat(roles(email)).containsExactlyInAnyOrder(1,2);
    assertThat(jdbc.queryForObject(
        "select bootstrap_completed from instance_registration_policy where singleton=true",Boolean.class))
        .isTrue();
    return account(email, true);
  }

  private Account ordinaryAccount(Account admin) throws Exception {
    String email=email("ordinary");
    assertThat(send("POST",SIGNUP,null,signup(email,legacyCode())).status()).isEqualTo(200);
    return account(email,false);
  }

  private Account account(String email, boolean admin) {
    UUID id=jdbc.queryForObject("select id from \"user\" where email=?",UUID.class,email);
    return new Account(id,email,jwt.generateToken(id,email,"Synthetic registration user",
        admin?Set.of("ROLE_ADM","ROLE_USER"):Set.of("ROLE_USER")));
  }

  private List<Integer> roles(String email) {
    return jdbc.queryForList("select roles from user_roles where user_id=(select id from \"user\" where email=?)",
        Integer.class,email);
  }

  private String legacyCode() {
    String code="KB-"+(100000000+java.util.concurrent.ThreadLocalRandom.current().nextInt(899999999));
    jdbc.update("insert into user_invites(code) values (?)",code);
    return code;
  }

  private boolean inviteExists(String code) {
    return jdbc.queryForObject("select exists(select 1 from user_invites where code=?)", Boolean.class,code);
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from "+table,Long.class);
  }

  private static String email(String prefix) { return prefix+"-"+UUID.randomUUID()+"@example.invalid"; }
  private static String mode(String value) { return JSON.createObjectNode().put("mode",value).toString(); }
  private static String waitlist(String address) { return JSON.createObjectNode().put("email",address).toString(); }
  private static String signup(String address,String code) {
    var body=JSON.createObjectNode().put("name","Synthetic registration user").put("email",address)
        .put("password","FixturePassword1!").put("notify",false);
    if(code!=null)body.put("invite_code",code);
    return body.toString();
  }
  private static String requestId(List<JsonNode> rows,String address) {
    return rows.stream().filter(row->address.equals(row.path("email").asText())).findFirst().orElseThrow()
        .required("request_id").asText();
  }

  private void setMode(Account account,String value) throws Exception {
    assertMode(send("PUT",ADMIN_MODE,account.token(),mode(value)),value);
    assertMode(send("GET",PUBLIC_MODE,null,null),value);
  }

  private HttpRequest request(String method,String path,String token,String body) {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .timeout(Duration.ofSeconds(15)).header("Accept","application/json");
    if(token!=null)builder.header("Authorization","Bearer "+token);
    if(body!=null)builder.header("Content-Type","application/json");
    return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private Response send(String method,String path,String token,String body) throws Exception {
    return response(http.send(request(method,path,token,body),HttpResponse.BodyHandlers.ofString()));
  }

  private Response response(HttpResponse<String> result) throws Exception {
    return new Response(result.statusCode(),result.headers(),
        result.body().isBlank()?NullNode.instance:JSON.readTree(result.body()));
  }

  private static void assertNoStore(Response response,int status) {
    assertThat(response.status()).as("response: %s",response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Cache-Control"))
        .hasValueSatisfying(value->assertThat(value).contains("no-store"));
  }

  private static void assertMode(Response response,String value) {
    assertNoStore(response,200);
    assertThat(response.body()).isEqualTo(JSON.createObjectNode().put("mode",value));
  }

  private static void assertError(Response response,int status,String code) {
    assertNoStore(response,status);
    assertThat(response.body().path("code").asText()).isEqualTo(code);
  }

  private static void assertInviteRequired(Response response) {
    assertThat(response.status()).as("signup response: %s",response.body()).isEqualTo(400);
    assertThat(response.body().path("code").asText()).isEqualTo("registration.invite_required");
    assertThat(response.body().path("error").asBoolean()).isTrue();
    assertThat(response.body().path("status").asInt()).isEqualTo(400);
  }

  private record Account(UUID id,String email,String token) {}
  private record Response(int status,HttpHeaders headers,JsonNode body) {}
}
