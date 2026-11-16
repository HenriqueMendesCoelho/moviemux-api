package com.moviemux.mail.application.spring.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class MailRestClientConfig {
  @Bean
  public RestClient restClientMail(ObjectMapper mapper, @Value("${mail.url}") String url) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder()
        .baseUrl(url)
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .messageConverters(
            converters -> {
              converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
              converters.add(new MappingJackson2HttpMessageConverter(mapper));
            })
        .build();
  }
}
