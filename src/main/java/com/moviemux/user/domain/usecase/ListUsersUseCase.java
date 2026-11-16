package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ListUsersUseCase {
  private final UserRepository repository;

  public List<User> execute() throws UserNotFoundException {
    var users = repository.findAll();
    if (users == null || users.isEmpty()) throw new UserNotFoundException();
    return users;
  }
}
