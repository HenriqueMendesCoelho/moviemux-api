package com.moviemux.user.domain.usecase.impl;

import com.moviemux.user.domain.repository.AccountRemoval;
import com.moviemux.user.domain.usecase.DeleteUserUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteUserUseCaseImpl implements DeleteUserUseCase {
  private final AccountRemoval removal;

  public void execute(UUID id) {
    removal.remove(id);
  }
}
