package com.moviemux.core.application.spring.configuration;

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

	@Value("${socket.io.api.url}")
	private String socketApiUrl;

	@Value("${socket.io.api.key}")
	private String socketApiKey;

	@Bean
	RestClient restClientSocketApi(ObjectMapper objectMapper) {
		return RestClient.builder()
				.baseUrl(socketApiUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer %s".formatted(socketApiKey))
				.messageConverters(converters -> {
					converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
					converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
				})
				.build();
	}

}
