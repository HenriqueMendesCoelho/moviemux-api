package com.moviemux.application.spring.bridge;

import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.user.domain.repository.UserIdentityWriter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MovieIdentityBridge implements MovieIdentityRepository {
  private final UserIdentityLookup identities;
  private final UserIdentityWriter writer;

  public MovieIdentityBridge(UserIdentityLookup identities, UserIdentityWriter writer) {
    this.identities = identities;
    this.writer = writer;
  }

  public Optional<MovieActor> findById(UUID id) {
    return identities.find(id).map(MovieActor::from);
  }

  public MovieActor findByEmail(String email) {
    return identities.findByEmail(email).map(MovieActor::from).orElse(null);
  }

  public void flush(UUID id) {
    writer.flush(id);
  }
}
