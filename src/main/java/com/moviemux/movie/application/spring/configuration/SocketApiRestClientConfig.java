package com.moviemux.movie.application.spring.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class SocketApiRestClientConfig {
  @Bean
  public RestClient restClientSocketApi(ObjectMapper mapper, @Value("${socket.io.api.url}") String url,
      @Value("${socket.io.api.key}") String apiKey) {
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
}
