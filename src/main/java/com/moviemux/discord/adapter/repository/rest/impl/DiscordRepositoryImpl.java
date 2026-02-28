package com.moviemux.discord.adapter.repository.rest.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.DiscordRepository;
import com.moviemux.discord.adapter.repository.rest.dto.DiscordWebhookRequestDto;
import com.moviemux.discord.adapter.repository.rest.dto.DiscordWebhookResponseDto;
import com.moviemux.discord.domain.DiscordWebhookInfo;
import com.moviemux.movie.domain.Movie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

@Repository
@Slf4j
@RequiredArgsConstructor
public class DiscordRepositoryImpl implements DiscordRepository {

	@Value("${discord.webhook.execute}")
	private boolean execute;

	@Value("${discord.webhook.update}")
	private boolean update;

	@Value("${discord.webhook.role.id}")
	private String roleId;

	private final RestClient restClientDiscord;
	private final ObjectMapper mapper;

	@Override
	public DiscordWebhookInfo execute(Movie movie) {
		if (!execute) {
			return null;
		}

		DiscordWebhookRequestDto request = new DiscordWebhookRequestDto(movie, roleId);
		try {
			DiscordWebhookResponseDto response = restClientDiscord.post()
					.uri("?wait=true")
					.body(request)
					.retrieve()
					.body(DiscordWebhookResponseDto.class);
			return response.toDomain();
		} catch (Exception e) {
			log.error("Error on Discord Webhook create message request raised: ", e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public DiscordWebhookInfo update(Movie movie) {
		if (!update || movie.getMovieDiscord() == null || StringUtils.isBlank(movie.getMovieDiscord().getMessageId())) {
			return null;
		}

		String messageId = movie.getMovieDiscord().getMessageId();
		DiscordWebhookRequestDto request = new DiscordWebhookRequestDto(movie, roleId);
		try {
			DiscordWebhookResponseDto response = restClientDiscord.patch()
					.uri("/messages/%s?wait=true".formatted(messageId))
					.body(request)
					.retrieve()
					.body(DiscordWebhookResponseDto.class);
			return response.toDomain();
		} catch (Exception e) {
			log.error("Error on Discord Webhook update message request raised: ", e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

}
