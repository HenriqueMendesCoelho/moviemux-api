package com.kronusboss.cine.application.spring.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

	@Value("${discord.webhook.url}")
	private String webhookUrl;

	@Value("${kit.key}")
	private String apiKeyKit;

	@Value("${kit.url}")
	private String kitUrl;

	@Bean
	WebClient webClientDiscord() {
		return WebClient.builder()
				.baseUrl(webhookUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	@Bean
	WebClient webClientKit() {
		return WebClient.builder()
				.baseUrl(kitUrl)
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.defaultHeader(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", apiKeyKit))
				.build();
	}

}