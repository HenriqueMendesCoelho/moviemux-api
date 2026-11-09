package com.moviemux.discord.domain.repository;

import com.moviemux.discord.domain.model.WebhookDelivery;

public interface WebhookRepository {
  WebhookDelivery send(WebhookMovieSource movie);

  WebhookDelivery edit(WebhookMovieSource movie);
}
