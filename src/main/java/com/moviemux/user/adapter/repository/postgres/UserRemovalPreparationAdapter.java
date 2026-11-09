package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.repository.AccountRemovalPreparation;
import com.moviemux.user.domain.repository.UserRemovalTarget;
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
    user.getId();
    return new UserRemovalTarget() {
      @Override
      public UUID userId() {
        return id;
      }

      @Override
      public void deleteAfterMoviesDetached() {
        users.delete(user);
      }
    };
  }
}
