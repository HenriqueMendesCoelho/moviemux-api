package com.moviemux.movieboundary;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ManualCatalogMovieHttpIT {
  private static final String SCHEMA="manual_movie_http_test_"+UUID.randomUUID().toString().replace("-","");
  private static final String PATH="/restricted/movies/catalog/manual";
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final AtomicInteger OUTBOUND=new AtomicInteger();
  private static String databaseUrl,databaseUser,databasePassword;
  private static boolean ownsSchema;
  private static HttpServer externalStub;

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource source;
  @Autowired private JWTUtil jwt;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry properties) throws Exception {
    databaseUrl=System.getenv("TEST_DATABASE_URL");
    if(databaseUrl==null || !databaseUrl.matches(
        "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*"))
      throw new IllegalStateException("Only a disposable loopback PostgreSQL test database is supported");
    databaseUser=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    databasePassword=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    try(var connection=DriverManager.getConnection(databaseUrl,databaseUser,databasePassword);
        var statement=connection.createStatement()){
      statement.execute("create schema "+SCHEMA);
      ownsSchema=true;
    }
    properties.add("spring.datasource.url",()->databaseUrl);
    properties.add("spring.datasource.hikari.schema",()->SCHEMA);
    properties.add("spring.flyway.default-schema",()->SCHEMA);
    properties.add("spring.flyway.schemas",()->SCHEMA);
    properties.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    properties.add("spring.datasource.hikari.connection-init-sql",()->"SET statement_timeout = '10s'");
    externalStub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    externalStub.createContext("/",exchange->{
      OUTBOUND.incrementAndGet();
      exchange.sendResponseHeaders(204,-1);
      exchange.close();
    });
    externalStub.start();
    String stub="http://127.0.0.1:"+externalStub.getAddress().getPort();
    properties.add("kit.url",()->stub);
    properties.add("socket.io.api.url",()->stub);
    properties.add("discord.webhook.url",()->stub);
    properties.add("discord.webhook.execute",()->true);
    properties.add("discord.webhook.update",()->true);
  }

  @AfterAll static void cleanup() throws Exception {
    if(externalStub!=null)externalStub.stop(0);
    if(ownsSchema){
      try(var connection=DriverManager.getConnection(databaseUrl,databaseUser,databasePassword);
          var statement=connection.createStatement()){
        statement.execute("drop schema "+SCHEMA+" cascade");
      }
    }
  }

  @Test void currentGlobalAdminProofAloneAndNoOwnerOrStaleRoleFallback() throws Exception {
    var admin=user(true);
    var owner=user(false);
    ranking(owner);
    UUID command=UUID.randomUUID();
    String payload=body(command,"Authority proof");
    assertThat(send("GET",PATH,null,null).status()).isEqualTo(401);
    assertThat(send("POST",PATH,null,payload).status()).isEqualTo(401);
    assertThat(send("GET",PATH,owner.token(),null).status()).isEqualTo(403);
    assertThat(send("POST",PATH,owner.token(),payload).status()).isEqualTo(403);
    var proof=send("GET",PATH,admin.token(),null);
    assertNoStore(proof,204);
    assertThat(proof.body().isNull()).isTrue();
    assertThat(commandCount(command)).isZero();

    jdbc.update("delete from user_roles where user_id=? and roles=1",admin.id());
    assertThat(send("GET",PATH,admin.token(),null).status()).isEqualTo(403);
    assertThat(send("POST",PATH,admin.token(),payload).status()).isEqualTo(403);
    jdbc.update("insert into user_roles(user_id,roles) values (?,1)",admin.id());
    jdbc.update("update user_statistics set consecutive_failed_login_attempts=10 where user_id=?",admin.id());
    assertThat(send("GET",PATH,admin.token(),null).status()).isIn(401,403);
    assertThat(send("POST",PATH,admin.token(),payload).status()).isIn(401,403);
    assertThat(commandCount(command)).isZero();
  }

  @Test void normalizedConcurrentReplaySameUuidWithCanonicalIsolationAndScopedDiscovery() throws Exception {
    var first=user(true);
    var second=user(true);
    var owner=user(false);
    UUID rank=ranking(owner), command=UUID.randomUUID();
    String title="Synthetic manual canonical "+UUID.randomUUID();
    long genreA=genre("Manual genre A"),genreB=genre("Manual genre B");
    var effectState=effectState();
    var decorated=JSON.createObjectNode().put("command_id",command.toString())
        .put("english_title","  "+title+"  ").putNull("portuguese_title")
        .put("description","  first\nsecond   ").put("release_date","2001-01-02").put("runtime",93);
    decorated.putArray("genre_ids").add(genreB).add(genreA);
    var normalized=JSON.createObjectNode().put("command_id",command.toString())
        .put("english_title",title).put("description","first\nsecond").put("release_date","2001-01-02").put("runtime",93);
    normalized.putArray("genre_ids").add(genreA).add(genreB);
    final Response firstResult,secondResult;
    try(var blocker=source.getConnection()){
      blocker.setAutoCommit(false);
      int pid=backendPid(blocker);
      try(var lock=blocker.prepareStatement("select id from movie_genre where id=? for update")){
        lock.setLong(1,genreA);
        try(var rows=lock.executeQuery()){assertThat(rows.next()).isTrue();}
      }
      var a=http.sendAsync(request("POST",PATH,first.token(),decorated.toString()),HttpResponse.BodyHandlers.ofString());
      var b=http.sendAsync(request("POST",PATH,second.token(),normalized.toString()),HttpResponse.BodyHandlers.ofString());
      try{awaitLocks(pid,"%from movie_genre%for key share%",List.of(a,b));}
      finally{blocker.commit();}
      firstResult=response(a.get(12,TimeUnit.SECONDS));
      secondResult=response(b.get(12,TimeUnit.SECONDS));
    }
    assertThat(List.of(firstResult.status(),secondResult.status())).containsExactlyInAnyOrder(200,201);
    assertNoStore(firstResult,firstResult.status());assertNoStore(secondResult,secondResult.status());
    assertThat(firstResult.body()).isEqualTo(secondResult.body());
    assertThat(firstResult.body().size()).isEqualTo(1);
    UUID movie=UUID.fromString(firstResult.body().required("movie_id").asText());
    assertThat(movie).isNotEqualTo(command);
    assertThat(commandCount(command)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*) from movie
        where id=? and manual_command_id=? and manual_command_version=1
          and length(manual_command_fingerprint)=64 and not legacy_eligible
          and user_id is null and tmdb_id is null and imdb_id is null and show_notes is null
          and original_title is null and portuguese_title is null and director is null
          and english_title=? and description=? and runtime=93 and release_date=date '2001-01-02'
        """,Long.class,movie,command,title,"first\nsecond")).isEqualTo(1);
    assertThat(jdbc.queryForList("select genre_id from movies_genres where movie_id=? order by genre_id",Long.class,movie))
        .containsExactly(genreA,genreB);
    assertError(send("POST",PATH,first.token(),body(command,"Changed title")),409,"catalog.manual_command_conflict");

    var listing=send("POST","/movies/collections/"+rank+"/entries/catalog/search",owner.token(),
        JSON.createObjectNode().put("query",title).put("page",1).put("language","en-US").toString());
    assertNoStore(listing,200);
    assertThat(listing.body().at("/items/0/movie_id").asText()).isEqualTo(movie.toString());
    var old=send("GET","/movie",first.token(),null);
    assertThat(old.status()).isIn(200,204);
    assertThat(old.body().toString()).doesNotContain(title,movie.toString());
    assertThat(effectState()).isEqualTo(effectState);
  }

  @Test void unknownGenreRollsBackAndIndependentHomonymCommandsDoNotMerge() throws Exception {
    var admin=user(true);
    UUID command=UUID.randomUUID();
    var before=effectState();
    String title="Synthetic homonym "+UUID.randomUUID();
    var invalid=JSON.createObjectNode().put("command_id",command.toString()).put("original_title",title);
    invalid.putArray("genre_ids").add(9007199254740991L);
    assertError(send("POST",PATH,admin.token(),invalid.toString()),400,"catalog.invalid_manual_movie");
    assertThat(commandCount(command)).isZero();
    var a=send("POST",PATH,admin.token(),body(command,title));
    var b=send("POST",PATH,admin.token(),body(UUID.randomUUID(),title));
    assertNoStore(a,201);assertNoStore(b,201);
    assertThat(a.body().required("movie_id")).isNotEqualTo(b.body().required("movie_id"));
    assertThat(effectState()).isEqualTo(before);
  }

  @Test void transportRejectsUnsupportedAndMalformedFieldsWithoutWriting() throws Exception {
    var admin=user(true);
    UUID command=UUID.randomUUID();
    var movie=JSON.createObjectNode().put("command_id",command.toString()).put("english_title","Synthetic");
    for(var bad:List.of(movie.deepCopy().put("tmdb_id",5).toString(),
        movie.deepCopy().put("release_date","2026-02-30").toString(), movie.deepCopy().put("runtime",2.4).toString(),
        movie.deepCopy().putNull("genre_ids").toString(), movie.deepCopy().put("command_id",new UUID(0,0).toString()).toString(),
        movie.toString()+" {}", movie.toString()+" ".repeat(32769),
        movie.toString().replace("}",",\"english_title\":\"Duplicate\"}"))) {
      assertError(send("POST",PATH,admin.token(),bad),400,"catalog.invalid_manual_movie");
    }
    assertError(send("GET",PATH+"?override=1",admin.token(),null),400,"catalog.invalid_manual_movie");
    assertError(send("POST",PATH+"?override=1",admin.token(),movie.toString()),400,"catalog.invalid_manual_movie");
    assertThat(commandCount(command)).isZero();
  }

  @Test void revocationHoldingTheActualRoleRowWinsBeforeManualInsert() throws Exception {
    var admin=user(true);
    UUID command=UUID.randomUUID();
    try(var blocker=source.getConnection()){
      blocker.setAutoCommit(false);
      int pid=backendPid(blocker);
      try(var revoke=blocker.prepareStatement("delete from user_roles where user_id=? and roles=1")){
        revoke.setObject(1,admin.id());assertThat(revoke.executeUpdate()).isEqualTo(1);
      }
      var pending=http.sendAsync(request("POST",PATH,admin.token(),body(command,"Race winner test")),
          HttpResponse.BodyHandlers.ofString());
      try {
        awaitLocks(pid,"%for key share of u for share of state,role%",List.of(pending));
        assertThat(commandCount(command)).isZero();
      } finally {blocker.commit();}
      assertThat(response(pending.get(12,TimeUnit.SECONDS)).status()).isEqualTo(403);
    }
    assertThat(commandCount(command)).isZero();
  }

  private int backendPid(java.sql.Connection blocker) throws Exception {
    try(var statement=blocker.createStatement();var rows=statement.executeQuery("select pg_backend_pid()")){
      assertThat(rows.next()).isTrue();return rows.getInt(1);
    }
  }

  private void awaitLocks(int pid,String queryPattern,List<? extends java.util.concurrent.Future<?>> pending) throws Exception {
    long deadline=System.nanoTime()+Duration.ofSeconds(7).toNanos();
    while(System.nanoTime()<deadline && pending.stream().noneMatch(java.util.concurrent.Future::isDone)){
      long waiting=jdbc.queryForObject("""
          with recursive blocked(pid) as (
            values (cast(? as integer))
            union
            select a.pid from pg_stat_activity a join blocked b on b.pid=any(pg_blocking_pids(a.pid))
            where a.wait_event_type='Lock'
          )
          select count(*) from pg_stat_activity a join blocked b using(pid)
          where a.wait_event_type='Lock' and a.query like ?
          """,Long.class,pid,queryPattern);
      if(waiting==pending.size())return;
      Thread.sleep(20);
    }
    throw new AssertionError("Manual create did not wait for the expected authority/reference row");
  }

  private List<Long> effectState(){
    return List.of(count("ranking_movie_entry"),count("ranking_movie_round"),count("movie_note"),count("movie_discord"),
        count("user_wishlist"),(long)OUTBOUND.get());
  }
  private long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
  private long commandCount(UUID command){
    return jdbc.queryForObject("select count(*) from movie where manual_command_id=?",Long.class,command);
  }
  private long genre(String prefix){
    return jdbc.queryForObject("insert into movie_genre(name) values (?) returning id",Long.class,prefix+" "+UUID.randomUUID());
  }

  private Account user(boolean admin){
    UUID id=UUID.randomUUID();String email="manual-"+id+"@example.invalid";
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic account','not-a-real-hash')",id,email);
    jdbc.update("insert into user_statistics(user_id,consecutive_failed_login_attempts) values (?,0)",id);
    jdbc.update("insert into user_preferences(user_id,notify) values (?,false)",id);
    jdbc.update("insert into user_roles(user_id,roles) values (?,2)",id);
    if(admin)jdbc.update("insert into user_roles(user_id,roles) values (?,1)",id);
    return new Account(id,jwt.generateToken(id,email,"Synthetic account",admin?Set.of("ROLE_USER","ROLE_ADM"):Set.of("ROLE_USER")));
  }

  private UUID ranking(Account owner) throws Exception {
    UUID id=UUID.randomUUID();
    try(var connection=source.getConnection()){
      connection.setAutoCommit(false);
      try(var ranking=connection.prepareStatement("""
            insert into ranking(id,owner_user_id,visibility,admission,created_at)
            values (?,?,'PRIVATE','INVITATION',now())
            """);var membership=connection.prepareStatement("""
            insert into ranking_membership(ranking_id,user_id,active,created_at) values (?, ?, true, now())
            """)){
        ranking.setObject(1,id);ranking.setObject(2,owner.id());ranking.executeUpdate();
        membership.setObject(1,id);membership.setObject(2,owner.id());membership.executeUpdate();
        connection.commit();
      }catch(Exception failure){connection.rollback();throw failure;}
    }
    return id;
  }

  private String body(UUID command,String title){
    return JSON.createObjectNode().put("command_id",command.toString()).put("english_title",title).toString();
  }
  private HttpRequest request(String method,String path,String jwtToken,String body){
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/monolith"+path))
        .timeout(Duration.ofSeconds(10)).header("Accept","application/json");
    if(jwtToken!=null)builder.header("Authorization","Bearer "+jwtToken);
    if(body!=null)builder.header("Content-Type","application/json");
    return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
  }
  private Response send(String method,String path,String jwtToken,String body) throws Exception {
    return response(http.send(request(method,path,jwtToken,body),HttpResponse.BodyHandlers.ofString()));
  }
  private Response response(HttpResponse<String> result) throws Exception {
    return new Response(result.statusCode(),result.headers(),result.body().isBlank()?NullNode.instance:JSON.readTree(result.body()));
  }
  private void assertNoStore(Response value,int status){
    assertThat(value.status()).as("response %s",value.body()).isEqualTo(status);
    assertThat(value.headers().firstValue("Cache-Control")).hasValueSatisfying(header->assertThat(header).contains("no-store"));
  }
  private void assertError(Response value,int status,String code){
    assertNoStore(value,status);
    assertThat(value.body()).isEqualTo(JSON.createObjectNode().put("code",code));
  }

  private record Account(UUID id,String token){}
  private record Response(int status,HttpHeaders headers,JsonNode body){}
}
