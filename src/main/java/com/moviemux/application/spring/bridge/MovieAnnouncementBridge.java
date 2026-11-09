package com.moviemux.application.spring.bridge;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.discord.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import org.springframework.stereotype.Component;
import java.util.UUID;
@Component
public class MovieAnnouncementBridge implements MovieAnnouncement {
 private final SendMessageWebhookUseCase create;private final UpdateMessageWebhookUseCase update;
 public MovieAnnouncementBridge(SendMessageWebhookUseCase create,UpdateMessageWebhookUseCase update){this.create=create;this.update=update;}
 public void created(UUID id){create.sendMovieMessage(id);}
 public void updated(UUID id){update.updateMovieMessage(id);}
}
