package com.moviemux.discord.adapter.repository.rest.impl;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

@Repository
@Slf4j
public class DiscordRepositoryImpl implements WebhookRepository {

  private final boolean execute;
  private final boolean update;
  private final String roleId;
  private final RestClient restClientDiscord;

  public DiscordRepositoryImpl(
      @Qualifier("restClientDiscord") RestClient restClientDiscord,
      @Value("${discord.webhook.execute}") boolean execute,
      @Value("${discord.webhook.update}") boolean update,
      @Value("${discord.webhook.role.id}") String roleId) {
    this.restClientDiscord = restClientDiscord;
    this.execute = execute;
    this.update = update;
    this.roleId = roleId;
  }

  @Override
  public WebhookDelivery send(WebhookMovieSource source) {
    if (!execute) {
      return WebhookDelivery.skipped(WebhookDelivery.Status.DISABLED);
    }

    WebhookMovie movie = source.snapshot();
    DiscordWebhookRequestDto request = WebhookRequestMapper.toRequest(movie, roleId);
    try {
      DiscordWebhookResponseDto response =
          restClientDiscord
              .post()
              .uri("?wait=true")
              .body(request)
              .retrieve()
              .body(DiscordWebhookResponseDto.class);
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
          restClientDiscord
              .patch()
              .uri(String.format("/messages/%s?wait=true", messageId))
              .body(request)
              .retrieve()
              .body(DiscordWebhookResponseDto.class);
      return WebhookDelivery.sent(
          java.util.Objects.requireNonNull(response, "Missing webhook response").toDomain());
    } catch (Exception e) {
      log.error("Error on Discord Webhook update message request raised: ", e);
      throw new WebhookFailure(e);
    }
  }
}
