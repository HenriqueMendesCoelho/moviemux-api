package com.moviemux.legacyboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LegacyBoardCutoverHttpIT {
  private static final String SUFFIX=UUID.randomUUID().toString().replace("-","");
  private static final String SCHEMA="cutover_http_"+SUFFIX,RUNTIME="app_role_"+SUFFIX,PASS="synthetic_only";
  private static final ObjectMapper JSON=new ObjectMapper();
  private static String url,ownerLogin,password;
  private static boolean ownsSchema,ownsRole;
  private static HttpServer external;
  private static JdbcTemplate jdbc;
  private static OfflineLegacyRankingMigration operator;
  private static final AtomicInteger OUTBOUND=new AtomicInteger();
  @LocalServerPort private int port;
  @Autowired private JWTUtil jwt;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource static void disposableEnvironment(DynamicPropertyRegistry props) throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    if(url==null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*"))
      throw new IllegalStateException("An isolated localhost database is required");
    ownerLogin=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    try(var c=DriverManager.getConnection(url,ownerLogin,password);var s=c.createStatement()){
      s.execute("create schema "+SCHEMA);ownsSchema=true;
      s.execute("create role "+RUNTIME+" login password '"+PASS+"'");ownsRole=true;
      s.execute("grant usage on schema "+SCHEMA+" to "+RUNTIME);
    }
    Flyway.configure().dataSource(url,ownerLogin,password).schemas(SCHEMA).defaultSchema(SCHEMA)
        .createSchemas(false).cleanDisabled(true).load().migrate();
    var operatorSource=new DriverManagerDataSource(url,ownerLogin,password) {
      @Override public Connection getConnection() throws SQLException {
        var c=super.getConnection();c.setSchema(SCHEMA);return c;
      }
    };
    jdbc=new JdbcTemplate(operatorSource);
    jdbc.execute("grant select,insert,update,delete on all tables in schema "+SCHEMA+" to "+RUNTIME);
    jdbc.execute("grant usage,select on all sequences in schema "+SCHEMA+" to "+RUNTIME);
    jdbc.execute("revoke all on legacy_board_cutover,flyway_schema_history from "+RUNTIME);
    operator=new OfflineLegacyRankingMigration(operatorSource,100,Set.of(RUNTIME));
    props.add("spring.datasource.url",()->url);
    props.add("spring.datasource.username",()->RUNTIME);
    props.add("spring.datasource.password",()->PASS);
    props.add("spring.datasource.hikari.schema",()->SCHEMA);
    props.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    props.add("spring.flyway.user",()->ownerLogin);
    props.add("spring.flyway.password",()->password);
    props.add("spring.flyway.url",()->url);
    props.add("spring.flyway.default-schema",()->SCHEMA);
    props.add("spring.flyway.schemas",()->SCHEMA);
    props.add("scheduling.enabled",()->false);
    props.add("send.mail",()->false);
    // Both old Discord handlers would dispatch if code accidentally crossed the gate.
    props.add("discord.webhook.execute",()->true);
    props.add("discord.webhook.update",()->true);
    external=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    external.createContext("/",exchange->{OUTBOUND.incrementAndGet();exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(500,-1);exchange.close();});
    external.start();
    for(String key:List.of("kit.url","mail.url","socket.io.api.url","discord.webhook.url"))
      props.add(key,()->"http://127.0.0.1:"+external.getAddress().getPort());
  }

  @AfterAll static void cleanupOwnedResources() throws Exception {
    if(external!=null)external.stop(0);
    if(ownsSchema||ownsRole)try(var c=DriverManager.getConnection(url,ownerLogin,password);var s=c.createStatement()){
      if(ownsSchema)s.execute("drop schema "+SCHEMA+" cascade");
      if(ownsRole){
        s.execute("select pg_terminate_backend(pid) from pg_stat_activity where usename='"+RUNTIME+"' and pid<>pg_backend_pid()");
        s.execute("drop role "+RUNTIME);
      }
    }
  }

  @Test void runtimeWithoutStateWritePrivilegesRetiresLegacyAndOnlyResolvesAuthorizedActiveMap() throws Exception {
    var owner=account(true);var member=account(false);UUID movie=UUID.randomUUID(),ranking=UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,show_notes,user_id,runtime)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',false,?,90)
        """,movie,owner.id());
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,member.id());
    assertThat(call("GET","/movie/"+movie,member.token(),null).statusCode()).isEqualTo(200);
    var baselineGenre=call("GET","/movie/genres",member.token(),null).statusCode();
    var baselineWishlist=call("GET","/user/wishlist",member.token(),null).statusCode();
    assertThat(baselineGenre).isIn(200,204);
    assertThat(baselineWishlist).isIn(200,204);
    assertThat(call("GET","/statistic/movies",member.token(),null).statusCode()).isEqualTo(200);
    String noteBody=JSON.createObjectNode().put("movie_id",movie.toString()).put("note",6).toString();
    String link="/movies/collections/legacy-film-links/"+movie,alias="/rankings/legacy-film-links/"+movie;
    assertCode(call("GET",link,member.token(),null),404,"ranking.not_found");

    jdbc.update("update movie set created_at=clock_timestamp()-interval '40 minutes' where id=?",movie);
    operator.fence(owner.id(),ranking,true);
    for(String path:List.of("/movie","/movie/"+movie,"/movie/"+UUID.randomUUID(),"/movie/note/"+movie,
        "/movie/genres-with-movies","/statistic/movies"))
      assertCode(call("GET",path,member.token(),null),410,"movie.legacy_board_retired");
    assertCode(call("PATCH","/movie/note/"+movie+"/update",member.token(),noteBody),410,"movie.legacy_board_retired");
    assertCode(call("DELETE","/movie/note/"+movie+"/delete",member.token(),null),410,"movie.legacy_board_retired");
    assertCode(call("POST","/restricted/discord/"+movie,owner.token(),null),410,"movie.legacy_board_retired");
    assertCode(call("PATCH","/restricted/discord/"+movie,owner.token(),null),410,"movie.legacy_board_retired");
    assertThat(call("GET","/movie/"+movie,null,null).statusCode()).isIn(401,403);
    assertCode(call("GET",link,member.token(),null),404,"ranking.not_found");
    assertThat(jdbc.queryForObject("select show_notes from movie where id=?",Boolean.class,movie)).isFalse();
    assertThat(jdbc.queryForObject("select note from movie_note where movie_id=?",Integer.class,movie)).isEqualTo(8);

    var plan=operator.preview(owner.id(),ranking);
    assertThat(operator.apply(plan,true).alreadyApplied()).isFalse();
    assertCode(call("GET","/movie/"+movie,member.token(),null),410,"movie.legacy_board_retired");
    assertCode(call("PATCH","/movie/note/"+movie+"/update",member.token(),noteBody),410,"movie.legacy_board_retired");
    assertThat(call("GET","/movie/genres",member.token(),null).statusCode()).isEqualTo(baselineGenre);
    assertThat(call("GET","/user/wishlist",member.token(),null).statusCode()).isEqualTo(baselineWishlist);
    assertThat(call("GET","/rankings/"+ranking+"/access",member.token(),null).statusCode()).isEqualTo(200);
    String linkedEntry=jdbc.queryForObject("select live_entry_id::text from legacy_ranking_movie_map where legacy_movie_id=?",String.class,movie);
    var handoff=call("GET",link,member.token(),null);
    assertThat(handoff.statusCode()).isEqualTo(200);
    assertThat(handoff.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(handoff.headers().firstValue("Location")).isEmpty();
    var expected=JSON.createObjectNode().put("ranking_id",ranking.toString()).put("entry_id",linkedEntry);
    assertThat(JSON.readTree(handoff.body())).isEqualTo(expected);
    assertThat(JSON.readTree(call("GET",alias,owner.token(),null).body())).isEqualTo(expected);
    assertThat(call("GET",link,null,null).statusCode()).isIn(401,403);
    jdbc.update("delete from ranking_membership_grant where ranking_id=? and user_id=? and capability='READ'",ranking,member.id());
    assertCode(call("GET",link,member.token(),null),404,"ranking.not_found");
    assertThat(jdbc.queryForObject("select revealed from ranking_movie_round where ranking_id=?",Boolean.class,ranking)).isFalse();
    assertThat(jdbc.queryForObject("select score from ranking_movie_rating where ranking_id=?",Integer.class,ranking)).isEqualTo(8);
    assertThat(OUTBOUND.get()).isZero();
    jdbc.update("delete from legacy_board_cutover where singleton"); // Simulated corruption in owned synthetic schema only.
    assertCode(call("GET","/movie/"+movie,member.token(),null),503,"movie.legacy_board_unavailable");
  }

  private Account account(boolean admin){
    UUID id=UUID.randomUUID();String email=id+"@example.invalid";
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",id,email);
    jdbc.update("insert into user_statistics(user_id,consecutive_failed_login_attempts) values (?,0)",id);
    jdbc.update("insert into user_preferences(user_id,notify) values (?,false)",id);
    jdbc.update("insert into user_roles(user_id,roles) values (?,2)",id);
    if(admin)jdbc.update("insert into user_roles(user_id,roles) values (?,1)",id);
    return new Account(id,jwt.generateToken(id,email,"Synthetic",admin?Set.of("ROLE_USER","ROLE_ADM"):Set.of("ROLE_USER")));
  }

  private HttpResponse<String> call(String method,String path,String token,String body) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .timeout(Duration.ofSeconds(8)).header("Accept","application/json")
        .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
    if(token!=null)request.header("Authorization","Bearer "+token);
    if(body!=null)request.header("Content-Type","application/json");
    return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }

  private void assertCode(HttpResponse<String> result,int status,String code) throws Exception {
    assertThat(result.statusCode()).isEqualTo(status);
    assertThat(result.headers().firstValue("Cache-Control")).hasValue("no-store");
    assertThat(result.headers().firstValue("Location")).isEmpty();
    assertThat(JSON.readTree(result.body())).isEqualTo(JSON.createObjectNode().put("code",code));
  }
  private record Account(UUID id,String token){}
}
