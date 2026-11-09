package com.moviemux.discord.domain.model;


public record WebhookDelivery(Status status, DiscordWebhookInfo info) {
  public enum Status {
    SENT,
    DISABLED,
    MISSING_REFERENCE
  }

  public static WebhookDelivery sent(DiscordWebhookInfo info) {
    return new WebhookDelivery(Status.SENT, info);
  }

  public static WebhookDelivery skipped(Status status) {
    return new WebhookDelivery(status, null);
  }
}
