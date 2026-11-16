package com.moviemux.user.domain.usecase;

import java.util.UUID;

public interface DeleteUserUseCase {

  void execute(UUID id);
}
