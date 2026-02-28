package com.moviemux.kronusintegrationtool.application.spring.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class KronusIntegrationToolRestClientConfig {

	@Value("${kit.url}")
	private String kitUrl;

	@Value("${kit.key}")
	private String apiKeyKit;

	@Bean
	RestClient restClientKit(ObjectMapper objectMapper) {
		return RestClient.builder()
				.baseUrl(kitUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer %s".formatted(apiKeyKit))
				.messageConverters(converters -> {
					converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
					converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
				})
				.build();
	}

}
