package com.moviemux.discord.adapter.repository.rest.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.dto.DiscordWebhookRequestDto;
import com.moviemux.discord.adapter.repository.rest.dto.DiscordWebhookResponseDto;
import com.moviemux.discord.adapter.repository.rest.mapper.WebhookRequestMapper;
import com.moviemux.discord.domain.exception.WebhookFailure;
import com.moviemux.discord.domain.model.WebhookDelivery;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.discord.domain.repository.WebhookRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;

@Repository
@Slf4j
public class DiscordRepositoryImpl implements WebhookRepository {

  @Value("${discord.webhook.execute}")
  private boolean execute;

  @Value("${discord.webhook.update}")
  private boolean update;

  @Value("${discord.webhook.role.id}")
  private String roleId;

  @Autowired private WebClient webClientDiscord;

  @Autowired private ObjectMapper mapper;

  @Override
  public WebhookDelivery send(WebhookMovieSource source) {
    if (!execute) {
      return WebhookDelivery.skipped(WebhookDelivery.Status.DISABLED);
    }

    WebhookMovie movie = source.snapshot();
    DiscordWebhookRequestDto request = WebhookRequestMapper.toRequest(movie, roleId);
    try {
      DiscordWebhookResponseDto response =
          webClientDiscord
              .post()
              .uri("?wait=true")
              .bodyValue(mapper.writeValueAsString(request))
              .retrieve()
              .bodyToMono(DiscordWebhookResponseDto.class)
              .block();
      return WebhookDelivery.sent(
          java.util.Objects.requireNonNull(response, "Missing webhook response").toDomain());
    } catch (Exception e) {
      log.error("Error on Discord Webhook create message request raised: ", e);
      throw new WebhookFailure(e);
    }
  }

  @Override
  public WebhookDelivery edit(WebhookMovieSource source) {
    if (!update) return WebhookDelivery.skipped(WebhookDelivery.Status.DISABLED);
    String messageId = source.messageId();
    if (StringUtils.isBlank(messageId))
      return WebhookDelivery.skipped(WebhookDelivery.Status.MISSING_REFERENCE);

    WebhookMovie movie = source.snapshot();
    DiscordWebhookRequestDto request = WebhookRequestMapper.toRequest(movie, roleId);
    try {
      DiscordWebhookResponseDto response =
          webClientDiscord
              .patch()
              .uri(String.format("/messages/%s?wait=true", messageId))
              .bodyValue(mapper.writeValueAsString(request))
              .retrieve()
              .bodyToMono(DiscordWebhookResponseDto.class)
              .block();
      return WebhookDelivery.sent(
          java.util.Objects.requireNonNull(response, "Missing webhook response").toDomain());
    } catch (Exception e) {
      log.error("Error on Discord Webhook update message request raised: ", e);
      throw new WebhookFailure(e);
    }
  }
}
