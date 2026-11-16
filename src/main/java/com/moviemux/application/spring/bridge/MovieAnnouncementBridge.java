package com.moviemux.application.spring.bridge;

import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.domain.usecase.UpdateMessageWebhookUseCase;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MovieAnnouncementBridge implements MovieAnnouncement {
  private final SendMessageWebhookUseCase create;
  private final UpdateMessageWebhookUseCase update;

  public MovieAnnouncementBridge(
      SendMessageWebhookUseCase create, UpdateMessageWebhookUseCase update) {
    this.create = create;
    this.update = update;
  }

  public void created(UUID id) {
    create.execute(id);
  }

  public void updated(UUID id) {
    update.execute(id);
  }
}
