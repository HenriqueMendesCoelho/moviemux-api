package com.moviemux.characterization;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.adapter.util.CredentialUtil;
import com.moviemux.application.spring.configuration.SecurityConfig;
import com.moviemux.application.spring.security.UserSS;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.user.domain.model.Role;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.*;

/** Real loopback HTTP + production filter chain, synthetic account lookup, no DB/providers. */
@SpringBootTest(classes=CredentialUtilHttpTest.Configuration.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1","server.servlet.context-path=/", "management.endpoints.enabled-by-default=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CredentialUtilHttpTest {
  @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
  @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,HibernateJpaAutoConfiguration.class,FlywayAutoConfiguration.class})
  @Import(SecurityConfig.class)
  static class Configuration {
    @Bean JWTUtil jwtUtil(){return CredentialUtilBase64UrlTest.issuer();}
    @Bean UserDetailsService users(){return email->new UserSS(CredentialUtilBase64UrlTest.ID,"Synthetic",email,"unused",0,Set.of(Role.USER));}
    @Bean ProjectionProbe probe(){return new ProjectionProbe();}
  }
  @RestController static class ProjectionProbe {
    final AtomicInteger calls=new AtomicInteger();
    @GetMapping(value="/movie/legacy-claim-probe",produces="text/plain;charset=UTF-8")
    String project(@RequestHeader("Authorization") String header){calls.incrementAndGet();return CredentialUtil.getUserFromToken(header).getName();}
  }
  @LocalServerPort int port;
  @Autowired JWTUtil jwt;
  @Autowired ProjectionProbe probe;
  private HttpResponse<String> get(String token)throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/movie/legacy-claim-probe"))
        .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
  }
  @Test void validUnicodeAndAsciiTokensReachLegacyProjectionOverRealHttp()throws Exception {
    for(char marker:new char[]{'-','_'}) {
      String token=CredentialUtilBase64UrlTest.unicodeToken(jwt,marker);
      var expected=CredentialUtil.getUserFromToken("Bearer "+token).getName();
      var response=get(token);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo(expected);
    }
    var response=get(jwt.generateToken(CredentialUtilBase64UrlTest.ID,CredentialUtilBase64UrlTest.EMAIL,"Synthetic ASCII",Set.of("ROLE_USER")));
    assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo("Synthetic ASCII");
  }
  @Test void decoderDoesNotBypassSignatureOrCurrentUuidAuthority()throws Exception {
    int before=probe.calls.get();
    var wrongKey=CredentialUtilBase64UrlTest.issuer();wrongKey.secret="synthetic-different-signing-key-never-production-0123456789";
    assertThat(get(wrongKey.generateToken(CredentialUtilBase64UrlTest.ID,CredentialUtilBase64UrlTest.EMAIL,"Synthetic",Set.of("ROLE_USER"))).statusCode()).isIn(401,403);
    assertThat(get(jwt.generateToken(UUID.randomUUID(),CredentialUtilBase64UrlTest.EMAIL,"Synthetic",Set.of("ROLE_ADM"))).statusCode()).isIn(401,403);
    assertThat(probe.calls.get()).isEqualTo(before);
  }
}
