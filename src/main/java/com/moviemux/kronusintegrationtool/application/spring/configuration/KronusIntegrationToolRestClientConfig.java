package com.moviemux.kronusintegrationtool.application.spring.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class KronusIntegrationToolRestClientConfig {
  @Bean
  public RestClient restClientKit(ObjectMapper mapper, @Value("${kit.url}") String url,
      @Value("${kit.key}") String apiKey) {
    return RestClient.builder()
        .baseUrl(url)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
        .messageConverters(converters -> {
          converters.removeIf(converter -> converter instanceof MappingJackson2HttpMessageConverter);
          converters.add(new MappingJackson2HttpMessageConverter(mapper));
        })
        .build();
  }

  /** Keep catalog summary time bounded without changing other KIT client policies. */
  @Bean
  public RestClient restClientKitSummary(@Qualifier("restClientKit") RestClient kit) {
    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var requests = new JdkClientHttpRequestFactory(client);
    requests.setReadTimeout(Duration.ofSeconds(10));
    return kit.mutate().requestFactory(requests).build();
  }
}
