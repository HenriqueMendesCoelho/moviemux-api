package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.repository.AccountRemovalPreparation;
import com.moviemux.user.domain.repository.UserRemovalTarget;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRemovalPreparationAdapter implements AccountRemovalPreparation {
  private final UserRepository users;

  @Override
  public UserRemovalTarget prepare(UUID id) {
    var user = users.getReferenceById(id);
    if (user == null) return null;
    var movieIds = user.getMovies().stream().map(movie -> movie.getId()).toList();
    return new UserRemovalTarget() {
      @Override
      public List<UUID> ownedMovieIds() {
        return movieIds;
      }

      @Override
      public void deleteAfterMoviesDetached() {
        user.setMovies(null);
        users.save(user);
        users.delete(user);
      }
    };
  }
}
